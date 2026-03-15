use std::collections::HashMap;
use std::io::{BufRead, BufReader, BufWriter, Write};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use anyhow::Result;
use ffl_shared::ipc::{DaemonEvent, UiEvent};

pub struct IpcServer {
    clients: Arc<Mutex<Vec<BufWriter<UnixStream>>>>,
    responses: Arc<Mutex<HashMap<String, bool>>>,
    last_status: Arc<Mutex<Option<String>>>,
}

impl IpcServer {
    pub fn bind(sock_path: &Path) -> Result<Self> {
        // Remove stale socket if present.
        let _ = std::fs::remove_file(sock_path);

        let listener = UnixListener::bind(sock_path)?;
        // Allow non-root users (e.g. the UI) to connect.
        std::fs::set_permissions(sock_path, std::os::unix::fs::PermissionsExt::from_mode(0o777))?;
        listener.set_nonblocking(true)?;

        let clients: Arc<Mutex<Vec<BufWriter<UnixStream>>>> = Arc::new(Mutex::new(Vec::new()));
        let responses: Arc<Mutex<HashMap<String, bool>>> = Arc::new(Mutex::new(HashMap::new()));
        let last_status: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));

        let clients_clone = Arc::clone(&clients);
        let responses_clone = Arc::clone(&responses);
        let last_status_clone = Arc::clone(&last_status);

        thread::spawn(move || {
            accept_loop(listener, clients_clone, responses_clone, last_status_clone);
        });

        Ok(Self { clients, responses, last_status })
    }

    /// Broadcast a DaemonEvent to all connected UI clients.
    /// Dead/disconnected clients are silently removed.
    pub fn broadcast(&self, event: &DaemonEvent) -> Result<()> {
        let line = serde_json::to_string(event)? + "\n";
        // Cache the last status so new clients get it immediately on connect.
        if matches!(event, DaemonEvent::Status(_)) {
            *self.last_status.lock().unwrap() = Some(line.clone());
        }
        let mut clients = self.clients.lock().unwrap();
        clients.retain_mut(|writer| {
            writer.write_all(line.as_bytes()).is_ok() && writer.flush().is_ok()
        });
        Ok(())
    }

    /// Check if a PromptResponse for the given id has arrived.
    /// Removes and returns the value if present.
    pub fn poll_response(&self, id: &str) -> Option<bool> {
        self.responses.lock().unwrap().remove(id)
    }
}

fn accept_loop(
    listener: UnixListener,
    clients: Arc<Mutex<Vec<BufWriter<UnixStream>>>>,
    responses: Arc<Mutex<HashMap<String, bool>>>,
    last_status: Arc<Mutex<Option<String>>>,
) {
    loop {
        match listener.accept() {
            Ok((stream, _)) => {
                if let Ok(writer_stream) = stream.try_clone() {
                    let mut writer = BufWriter::new(writer_stream);
                    // Push the last known status immediately so the UI doesn't
                    // show stale data while waiting for the next broadcast cycle.
                    if let Some(ref line) = *last_status.lock().unwrap() {
                        let _ = writer.write_all(line.as_bytes());
                        let _ = writer.flush();
                    }
                    clients.lock().unwrap().push(writer);
                }
                let responses_clone = Arc::clone(&responses);
                thread::spawn(move || {
                    read_loop(stream, responses_clone);
                });
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(10));
            }
            Err(_) => {
                thread::sleep(Duration::from_millis(100));
            }
        }
    }
}

fn read_loop(stream: UnixStream, responses: Arc<Mutex<HashMap<String, bool>>>) {
    let reader = BufReader::new(stream);
    for line in reader.lines() {
        match line {
            Ok(line) => {
                if let Ok(UiEvent::PromptResponse(resp)) = serde_json::from_str::<UiEvent>(&line) {
                    responses.lock().unwrap().insert(resp.id, resp.accepted);
                }
            }
            Err(_) => break,
        }
    }
}
