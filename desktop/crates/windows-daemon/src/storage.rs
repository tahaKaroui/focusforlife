use std::path::Path;

use anyhow::{Context, Result};
use chrono::{Local, NaiveDate};
use rusqlite::{params, Connection};

pub struct Storage {
    conn: Connection,
}

impl Storage {
    pub fn open(path: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .with_context(|| format!("failed to create db dir: {}", parent.display()))?;
        }
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

            create table if not exists prompt_state (
              day text primary key,
              free_time_granted integer not null,
              decided_at text not null
            );

            create table if not exists hourly_bucket (
              id integer primary key check (id = 1),
              hour_stamp integer not null default 0,
              used_seconds integer not null default 0
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

    pub fn get_hourly_bucket(&self) -> Result<(u64, u32)> {
        let mut stmt = self.conn.prepare(
            "select hour_stamp, used_seconds from hourly_bucket where id = 1",
        )?;
        let mut rows = stmt.query([])?;
        if let Some(row) = rows.next()? {
            let stamp: i64 = row.get(0)?;
            let used: i64 = row.get(1)?;
            Ok((stamp.max(0) as u64, used.max(0) as u32))
        } else {
            Ok((0, 0))
        }
    }

    pub fn set_hourly_bucket(&self, hour_stamp: u64, used_seconds: u32) -> Result<()> {
        self.conn.execute(
            "insert into hourly_bucket (id, hour_stamp, used_seconds) values (1, ?1, ?2)
             on conflict(id) do update set hour_stamp = excluded.hour_stamp,
                                           used_seconds = excluded.used_seconds",
            params![hour_stamp as i64, used_seconds as i64],
        )?;
        Ok(())
    }
}

#[derive(Debug, Default, Clone)]
pub struct DailyUsage {
    pub used_seconds: u32,
    pub sessions_count: u32,
}
