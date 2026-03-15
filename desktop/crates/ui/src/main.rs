use std::io::{self, Write};
use std::time::{Duration, Instant};

use anyhow::Result;
use eframe::egui;
use ffl_shared::ipc::{DaemonEvent, FocusState, PromptRequest, PromptResponse, StatusSnapshot, UiEvent};

mod daemon_conn;
use daemon_conn::DaemonConn;

fn main() -> Result<()> {
    // UI entrypoint (overlay + prompts).
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|a| a == "--prompt-test") {
        run_prompt_test()?;
        return Ok(());
    }

    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default().with_inner_size([420.0, 220.0]),
        ..Default::default()
    };

    if let Err(err) = eframe::run_native(
        "FocusForLife",
        options,
        Box::new(|_| Box::new(FflApp::new())),
    ) {
        eprintln!("ffl-ui failed to start: {err}");
    }
    Ok(())
}

fn run_prompt_test() -> Result<()> {
    println!("Done with work? [y/N]");
    print!("> ");
    io::stdout().flush()?;
    let mut input = String::new();
    io::stdin().read_line(&mut input)?;
    let accepted = matches!(input.trim().to_lowercase().as_str(), "y" | "yes");

    let response = UiEvent::PromptResponse(PromptResponse {
        id: "free_time_21_00".to_string(),
        accepted,
    });
    let json = serde_json::to_string_pretty(&response)?;
    println!("{json}");
    Ok(())
}

struct FflApp {
    conn: Option<DaemonConn>,
    status: Option<StatusSnapshot>,
    pending_prompt: Option<PromptRequest>,
    retry_at: Instant,
}

impl FflApp {
    fn new() -> Self {
        let conn = DaemonConn::connect(std::path::Path::new("/run/focusforlife/daemon.sock")).ok();
        Self {
            conn,
            status: None,
            pending_prompt: None,
            retry_at: Instant::now(),
        }
    }

    fn send_prompt_response(&mut self, id: &str, accepted: bool) {
        if let Some(ref mut conn) = self.conn {
            conn.send_response(PromptResponse {
                id: id.to_string(),
                accepted,
            })
            .ok();
        }
    }
}

impl eframe::App for FflApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // Retry connecting if disconnected.
        if self.conn.is_none() && self.retry_at.elapsed() > Duration::from_secs(3) {
            self.conn =
                DaemonConn::connect(std::path::Path::new("/run/focusforlife/daemon.sock")).ok();
            self.retry_at = Instant::now();
        }

        // Drain incoming events; detect dead connections so we reconnect.
        if self.conn.is_some() {
            loop {
                match self.conn.as_ref().unwrap().try_recv() {
                    Ok(Some(DaemonEvent::Status(snap))) => self.status = Some(snap),
                    Ok(Some(DaemonEvent::Prompt(req))) => self.pending_prompt = Some(req),
                    Ok(None) => break,
                    Err(()) => {
                        self.conn = None;
                        break;
                    }
                }
            }
        }

        // Keep UI refreshing even without input events.
        ctx.request_repaint_after(Duration::from_secs(2));

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("FocusForLife");
            ui.separator();

            match &self.status {
                None => {
                    ui.label("Connecting to daemon...");
                }
                Some(snap) => {
                    let state_label = match snap.state {
                        FocusState::Allowed => "Allowed",
                        FocusState::BlockedHardWindow => "Blocked (hard window)",
                        FocusState::BlockedCooldown => "Blocked (cooldown)",
                        FocusState::BlockedQuota => "Blocked (quota)",
                    };
                    ui.label(format!("State: {state_label}"));

                    let daily_rem =
                        snap.daily_quota_seconds.saturating_sub(snap.daily_used_seconds);
                    ui.label(format!(
                        "Daily remaining: {}m {}s",
                        daily_rem / 60,
                        daily_rem % 60
                    ));

                    let sess_rem =
                        snap.session_limit_seconds.saturating_sub(snap.session_used_seconds);
                    ui.label(format!(
                        "Session remaining: {}m {}s",
                        sess_rem / 60,
                        sess_rem % 60
                    ));

                    if snap.cooldown_remaining_seconds > 0 {
                        ui.label(format!(
                            "Cooldown: {}m {}s remaining",
                            snap.cooldown_remaining_seconds / 60,
                            snap.cooldown_remaining_seconds % 60
                        ));
                    }
                }
            }
        });

        // Prompt dialog — appears centered over main window.
        if let Some(prompt) = self.pending_prompt.clone() {
            egui::Window::new(&prompt.title)
                .collapsible(false)
                .resizable(false)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .show(ctx, |ui| {
                    ui.label(&prompt.message);
                    ui.add_space(8.0);
                    ui.horizontal(|ui| {
                        if ui.button("Yes").clicked() {
                            self.send_prompt_response(&prompt.id, true);
                            self.pending_prompt = None;
                        }
                        if ui.button("No").clicked() {
                            self.send_prompt_response(&prompt.id, false);
                            self.pending_prompt = None;
                        }
                    });
                });
        }
    }
}
