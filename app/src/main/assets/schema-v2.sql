CREATE TABLE reminder_queue (
    slot_key TEXT PRIMARY KEY NOT NULL,
    medication_id TEXT NOT NULL REFERENCES medications(id) ON DELETE RESTRICT,
    scheduled_date TEXT NOT NULL,
    scheduled_time TEXT NOT NULL,
    schedule_zone TEXT NOT NULL,
    next_at INTEGER,
    expires_at INTEGER NOT NULL,
    deliveries INTEGER NOT NULL DEFAULT 0 CHECK(deliveries >= 0)
);

CREATE INDEX next_reminder ON reminder_queue(next_at);
