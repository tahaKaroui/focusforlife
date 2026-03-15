use std::path::Path;

use anyhow::{Context, Result};
use chrono::{DateTime, Local, NaiveDate};
use rusqlite::{params, Connection};

pub struct Storage {
    conn: Connection,
}

impl Storage {
    pub fn open(path: &Path) -> Result<Self> {
        let conn = Connection::open(path)
            .with_context(|| format!("failed to open db: {}", path.display()))?;
        let storage = Self { conn };
        storage.init()?;
        Ok(storage)
    }

    fn init(&self) -> Result<()> {
        self.conn.execute_batch(
            r#"
            create table if not exists usage_daily (
              day text primary key,
              used_seconds integer not null default 0,
              sessions_count integer not null default 0,
              updated_at text not null
            );

            create table if not exists cooldown (
              id integer primary key check (id = 1),
              ends_at text not null
            );

            create table if not exists prompt_state (
              day text primary key,
              free_time_granted integer not null,
              decided_at text not null
            );

            create table if not exists sessions (
              id integer primary key autoincrement,
              start_at text not null,
              end_at text not null,
              duration_seconds integer not null,
              ended_by text not null
            );

            create table if not exists current_session (
              id integer primary key check (id = 1),
              start_at text not null,
              used_seconds integer not null
            );
            "#,
        )?;
        Ok(())
    }

    pub fn get_daily_usage(&self, day: NaiveDate) -> Result<DailyUsage> {
        let day_str = day.format("%Y-%m-%d").to_string();
        let mut stmt = self.conn.prepare(
            "select used_seconds, sessions_count from usage_daily where day = ?1",
        )?;
        let mut rows = stmt.query(params![day_str])?;
        if let Some(row) = rows.next()? {
            let used_seconds: i64 = row.get(0)?;
            let sessions_count: i64 = row.get(1)?;
            Ok(DailyUsage {
                used_seconds: used_seconds.max(0) as u32,
                sessions_count: sessions_count.max(0) as u32,
            })
        } else {
            Ok(DailyUsage::default())
        }
    }

    pub fn upsert_daily_usage(&self, day: NaiveDate, usage: &DailyUsage) -> Result<()> {
        let now = Local::now();
        let day_str = day.format("%Y-%m-%d").to_string();
        self.conn.execute(
            "insert into usage_daily (day, used_seconds, sessions_count, updated_at)
             values (?1, ?2, ?3, ?4)
             on conflict(day) do update set used_seconds = excluded.used_seconds,
                                             sessions_count = excluded.sessions_count,
                                             updated_at = excluded.updated_at",
            params![
                day_str,
                usage.used_seconds as i64,
                usage.sessions_count as i64,
                now.to_rfc3339(),
            ],
        )?;
        Ok(())
    }

    pub fn add_daily_usage_seconds(&self, day: NaiveDate, delta_seconds: u32) -> Result<()> {
        let mut usage = self.get_daily_usage(day)?;
        usage.used_seconds = usage.used_seconds.saturating_add(delta_seconds);
        self.upsert_daily_usage(day, &usage)
    }

    pub fn increment_sessions_count(&self, day: NaiveDate) -> Result<()> {
        let mut usage = self.get_daily_usage(day)?;
        usage.sessions_count = usage.sessions_count.saturating_add(1);
        self.upsert_daily_usage(day, &usage)
    }

    pub fn get_cooldown_end(&self) -> Result<Option<DateTime<Local>>> {
        let mut stmt = self.conn.prepare("select ends_at from cooldown where id = 1")?;
        let mut rows = stmt.query([])?;
        if let Some(row) = rows.next()? {
            let ends_at: String = row.get(0)?;
            let parsed = DateTime::parse_from_rfc3339(&ends_at)
                .map(|dt| dt.with_timezone(&Local))
                .ok();
            Ok(parsed)
        } else {
            Ok(None)
        }
    }

    pub fn set_cooldown_end(&self, ends_at: DateTime<Local>) -> Result<()> {
        self.conn.execute(
            "insert into cooldown (id, ends_at) values (1, ?1)
             on conflict(id) do update set ends_at = excluded.ends_at",
            params![ends_at.to_rfc3339()],
        )?;
        Ok(())
    }

    pub fn get_free_time_grant(&self, day: NaiveDate) -> Result<Option<bool>> {
        let day_str = day.format("%Y-%m-%d").to_string();
        let mut stmt = self.conn.prepare(
            "select free_time_granted from prompt_state where day = ?1",
        )?;
        let mut rows = stmt.query(params![day_str])?;
        if let Some(row) = rows.next()? {
            let granted: i64 = row.get(0)?;
            Ok(Some(granted != 0))
        } else {
            Ok(None)
        }
    }

    pub fn set_free_time_grant(&self, day: NaiveDate, granted: bool) -> Result<()> {
        let now = Local::now();
        let day_str = day.format("%Y-%m-%d").to_string();
        self.conn.execute(
            "insert into prompt_state (day, free_time_granted, decided_at)
             values (?1, ?2, ?3)
             on conflict(day) do update set free_time_granted = excluded.free_time_granted,
                                            decided_at = excluded.decided_at",
            params![day_str, if granted { 1 } else { 0 }, now.to_rfc3339()],
        )?;
        Ok(())
    }

    pub fn save_current_session(&self, start_at: DateTime<Local>, used_seconds: u32) -> Result<()> {
        self.conn.execute(
            "insert into current_session (id, start_at, used_seconds) values (1, ?1, ?2)
             on conflict(id) do update set start_at = excluded.start_at,
                                           used_seconds = excluded.used_seconds",
            params![start_at.to_rfc3339(), used_seconds as i64],
        )?;
        Ok(())
    }

    pub fn load_current_session(&self) -> Result<Option<(DateTime<Local>, u32)>> {
        let mut stmt = self.conn.prepare(
            "select start_at, used_seconds from current_session where id = 1",
        )?;
        let mut rows = stmt.query([])?;
        if let Some(row) = rows.next()? {
            let start_at: String = row.get(0)?;
            let used_seconds: i64 = row.get(1)?;
            let parsed = DateTime::parse_from_rfc3339(&start_at)
                .map(|dt| dt.with_timezone(&Local))
                .ok();
            Ok(parsed.map(|start| (start, used_seconds.max(0) as u32)))
        } else {
            Ok(None)
        }
    }

    pub fn clear_current_session(&self) -> Result<()> {
        self.conn.execute("delete from current_session where id = 1", [])?;
        Ok(())
    }

    pub fn record_session(
        &self,
        start_at: DateTime<Local>,
        end_at: DateTime<Local>,
        duration_seconds: u32,
        ended_by: &str,
    ) -> Result<()> {
        self.conn.execute(
            "insert into sessions (start_at, end_at, duration_seconds, ended_by)
             values (?1, ?2, ?3, ?4)",
            params![
                start_at.to_rfc3339(),
                end_at.to_rfc3339(),
                duration_seconds as i64,
                ended_by
            ],
        )?;
        Ok(())
    }
}

#[derive(Debug, Default, Clone)]
pub struct DailyUsage {
    pub used_seconds: u32,
    pub sessions_count: u32,
}
