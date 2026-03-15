use std::io::{BufRead, BufReader, BufWriter, Write};
use std::os::unix::net::UnixStream;
use std::path::Path;
use std::sync::mpsc::{self, Receiver};
use std::thread;

use anyhow::Result;
use ffl_shared::ipc::{DaemonEvent, PromptResponse, UiEvent};

pub struct DaemonConn {
    rx: Receiver<DaemonEvent>,
    writer: BufWriter<UnixStream>,
}

impl DaemonConn {
    pub fn connect(sock_path: &Path) -> Result<Self> {
        let stream = UnixStream::connect(sock_path)?;
        let reader_stream = stream.try_clone()?;

        let (tx, rx) = mpsc::channel();
        thread::spawn(move || {
            let reader = BufReader::new(reader_stream);
            for line in reader.lines() {
                match line {
                    Ok(line) => {
                        if let Ok(event) = serde_json::from_str::<DaemonEvent>(&line) {
                            if tx.send(event).is_err() {
                                break;
                            }
                        }
                    }
                    Err(_) => break,
                }
            }
        });

        Ok(Self {
            rx,
            writer: BufWriter::new(stream),
        })
    }

    /// Returns `Ok(Some(event))` on a new event, `Ok(None)` when the queue is
    /// empty, or `Err(())` when the connection to the daemon has been lost.
    pub fn try_recv(&self) -> Result<Option<DaemonEvent>, ()> {
        match self.rx.try_recv() {
            Ok(event) => Ok(Some(event)),
            Err(mpsc::TryRecvError::Empty) => Ok(None),
            Err(mpsc::TryRecvError::Disconnected) => Err(()),
        }
    }

    pub fn send_response(&mut self, resp: PromptResponse) -> Result<()> {
        let event = UiEvent::PromptResponse(resp);
        let line = serde_json::to_string(&event)? + "\n";
        self.writer.write_all(line.as_bytes())?;
        self.writer.flush()?;
        Ok(())
    }
}
