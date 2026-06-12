use std::fs;
use std::time::Duration;
use eframe::egui;

const STATUS_FILE: &str = r"C:\ProgramData\FocusForLife\status.json";
const SHIELD_PNG: &[u8] = include_bytes!("../../ui/assets/shield.png");

// Brand palette (matches the Android app and the shield logo).
const BG: egui::Color32 = egui::Color32::from_rgb(8, 29, 36);
const SURFACE: egui::Color32 = egui::Color32::from_rgb(14, 42, 51);
const RAISED: egui::Color32 = egui::Color32::from_rgb(21, 58, 70);
const OUTLINE: egui::Color32 = egui::Color32::from_rgb(44, 82, 93);
const CREAM: egui::Color32 = egui::Color32::from_rgb(246, 241, 231);
const MUTED: egui::Color32 = egui::Color32::from_rgb(159, 184, 190);
const ORANGE: egui::Color32 = egui::Color32::from_rgb(242, 163, 60);
const TEAL: egui::Color32 = egui::Color32::from_rgb(85, 163, 181);
const GREEN: egui::Color32 = egui::Color32::from_rgb(83, 184, 132);
const AMBER: egui::Color32 = egui::Color32::from_rgb(242, 179, 60);
const RED: egui::Color32 = egui::Color32::from_rgb(228, 88, 76);

fn main() {
    let mut viewport = egui::ViewportBuilder::default()
        .with_inner_size([360.0, 440.0])
        .with_min_inner_size([320.0, 380.0])
        .with_title("FocusForLife");
    if let Some(icon) = load_window_icon() {
        viewport = viewport.with_icon(std::sync::Arc::new(icon));
    }
    let options = eframe::NativeOptions {
        viewport,
        ..Default::default()
    };
    eframe::run_native(
        "FocusForLife",
        options,
        Box::new(|cc| {
            apply_brand_style(&cc.egui_ctx);
            Box::new(App::default())
        }),
    )
    .ok();
}

fn load_window_icon() -> Option<egui::IconData> {
    let img = image::load_from_memory(SHIELD_PNG).ok()?.into_rgba8();
    let (width, height) = img.dimensions();
    Some(egui::IconData {
        rgba: img.into_raw(),
        width,
        height,
    })
}

fn apply_brand_style(ctx: &egui::Context) {
    let mut style = (*ctx.style()).clone();
    style.visuals = egui::Visuals::dark();
    style.visuals.override_text_color = Some(CREAM);
    style.visuals.panel_fill = BG;
    style.visuals.window_fill = SURFACE;
    style.visuals.window_stroke = egui::Stroke::new(1.0, OUTLINE);
    style.visuals.widgets.noninteractive.bg_fill = SURFACE;
    style.visuals.widgets.noninteractive.bg_stroke = egui::Stroke::new(1.0, OUTLINE);
    style.visuals.widgets.inactive.bg_fill = RAISED;
    style.spacing.item_spacing = egui::vec2(8.0, 8.0);
    ctx.set_style(style);
}

#[derive(Default)]
struct App {
    daily_remaining: u32,
    hourly_remaining: u32,
    daily_quota: u32,
    hourly_limit: u32,
    hard_block_start: String,
    hard_block_end: String,
    state: String,
    status_ok: bool,
    logo: Option<egui::TextureHandle>,
}

fn fmt_duration(total_seconds: u32) -> String {
    let hrs = total_seconds / 3600;
    let mins = (total_seconds % 3600) / 60;
    let secs = total_seconds % 60;
    if hrs > 0 {
        format!("{hrs}h {mins:02}m")
    } else if mins > 0 {
        format!("{mins}m {secs:02}s")
    } else {
        format!("{secs}s")
    }
}

fn quota_bar(ui: &mut egui::Ui, label: &str, remaining: u32, total: u32, fill: egui::Color32) {
    let frac = if total == 0 {
        0.0
    } else {
        (remaining as f32 / total as f32).clamp(0.0, 1.0)
    };
    ui.horizontal(|ui| {
        ui.label(egui::RichText::new(label).color(MUTED).size(13.0));
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            ui.label(
                egui::RichText::new(format!(
                    "{} left of {}",
                    fmt_duration(remaining),
                    fmt_duration(total)
                ))
                .color(CREAM)
                .strong()
                .size(13.0),
            );
        });
    });
    ui.add(
        egui::ProgressBar::new(frac)
            .fill(fill)
            .desired_width(ui.available_width()),
    );
}


fn dot(ui: &mut egui::Ui, color: egui::Color32, radius: f32) {
    let (rect, _) = ui.allocate_exact_size(
        egui::vec2(radius * 2.0, radius * 2.0),
        egui::Sense::hover(),
    );
    ui.painter().circle_filled(rect.center(), radius, color);
}

fn section_frame() -> egui::Frame {
    egui::Frame::none()
        .fill(SURFACE)
        .stroke(egui::Stroke::new(1.0, OUTLINE))
        .rounding(egui::Rounding::same(12.0))
        .inner_margin(egui::Margin::same(14.0))
}

impl App {
    fn logo_texture(&mut self, ctx: &egui::Context) -> Option<egui::TextureHandle> {
        if self.logo.is_none() {
            let img = image::load_from_memory(SHIELD_PNG).ok()?.into_rgba8();
            let size = [img.width() as usize, img.height() as usize];
            let color = egui::ColorImage::from_rgba_unmultiplied(size, img.as_raw());
            self.logo = Some(ctx.load_texture("ffl-shield", color, Default::default()));
        }
        self.logo.clone()
    }

    fn badge(&self) -> (&'static str, String, egui::Color32) {
        match self.state.as_str() {
            "allowed" => (
                "WITHIN SAFE WINDOW",
                "Distracting sites are available — spend wisely.".to_string(),
                GREEN,
            ),
            "blocked_hard_window" => (
                "HIBERNATE WINDOW",
                if self.hard_block_start.is_empty() {
                    "Hard lockdown is active.".to_string()
                } else {
                    format!(
                        "Hard lockdown runs {} – {}.",
                        self.hard_block_start, self.hard_block_end
                    )
                },
                RED,
            ),
            "blocked_cooldown" => (
                "HOURLY COOLDOWN",
                "Hourly limit used. Unlocks at the top of the hour.".to_string(),
                AMBER,
            ),
            "blocked_quota" => (
                "DAILY QUOTA EXHAUSTED",
                "The shared daily allowance is gone — see you tomorrow.".to_string(),
                AMBER,
            ),
            _ => (
                "WAITING FOR DAEMON",
                "No status yet — is the FocusForLife daemon running?".to_string(),
                MUTED,
            ),
        }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _: &mut eframe::Frame) {
        self.status_ok = false;
        if let Ok(text) = fs::read_to_string(STATUS_FILE) {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) {
                self.daily_remaining = v["daily_remaining"].as_u64().unwrap_or(0) as u32;
                self.hourly_remaining = v["hourly_remaining"].as_u64().unwrap_or(0) as u32;
                self.daily_quota = v["daily_quota"].as_u64().unwrap_or(3600) as u32;
                self.hourly_limit = v["hourly_limit"].as_u64().unwrap_or(600) as u32;
                self.hard_block_start =
                    v["hard_block_start"].as_str().unwrap_or("").to_string();
                self.hard_block_end = v["hard_block_end"].as_str().unwrap_or("").to_string();
                self.state = v["state"].as_str().unwrap_or("unknown").to_string();
                self.status_ok = true;
            }
        }

        ctx.request_repaint_after(Duration::from_secs(1));
        let logo = self.logo_texture(ctx);

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.add_space(4.0);

            // ---- Brand header ----
            ui.horizontal(|ui| {
                if let Some(tex) = &logo {
                    let h = 44.0;
                    let w = h * tex.aspect_ratio();
                    ui.image((tex.id(), egui::vec2(w, h)));
                    ui.add_space(4.0);
                }
                ui.vertical(|ui| {
                    ui.horizontal(|ui| {
                        ui.spacing_mut().item_spacing.x = 0.0;
                        ui.label(egui::RichText::new("Focus").size(22.0).strong().color(CREAM));
                        ui.label(egui::RichText::new("For").size(22.0).strong().color(ORANGE));
                        ui.label(egui::RichText::new("Life").size(22.0).strong().color(CREAM));
                    });
                    ui.label(egui::RichText::new("Guard your attention").color(MUTED).size(12.0));
                });
            });
            ui.add_space(8.0);

            let (label, detail, color) = self.badge();

            // ---- Status banner ----
            egui::Frame::none()
                .fill(color.linear_multiply(0.16))
                .stroke(egui::Stroke::new(1.0, color))
                .rounding(egui::Rounding::same(12.0))
                .inner_margin(egui::Margin::same(14.0))
                .show(ui, |ui| {
                    ui.set_width(ui.available_width());
                    ui.horizontal(|ui| {
                        dot(ui, color, 5.0);
                        ui.label(
                            egui::RichText::new(label)
                                .color(color)
                                .strong()
                                .size(14.0),
                        );
                    });
                    ui.label(egui::RichText::new(detail).color(CREAM).size(12.5));
                });
            ui.add_space(8.0);

            // ---- Time left hero ----
            section_frame().show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.label(
                    egui::RichText::new(fmt_duration(self.daily_remaining))
                        .size(32.0)
                        .strong()
                        .color(CREAM),
                );
                ui.label(egui::RichText::new("daily time left").color(MUTED).size(12.0));
                ui.add_space(8.0);
                quota_bar(ui, "Today", self.daily_remaining, self.daily_quota, ORANGE);
                ui.add_space(4.0);
                quota_bar(ui, "This hour", self.hourly_remaining, self.hourly_limit, TEAL);
            });

            // ---- Footer ----
            ui.with_layout(egui::Layout::bottom_up(egui::Align::Center), |ui| {
                ui.add_space(6.0);
                let (dot_color, text) = if self.status_ok {
                    (GREEN, "Daemon connected")
                } else {
                    (RED, "Daemon offline — waiting for status…")
                };
                ui.horizontal(|ui| {
                    let total = ui.available_width();
                    let approx = 160.0;
                    ui.add_space((total - approx).max(0.0) / 2.0);
                    dot(ui, dot_color, 4.0);
                    ui.label(egui::RichText::new(text).color(MUTED).size(11.0));
                });
            });
        });
    }
}
