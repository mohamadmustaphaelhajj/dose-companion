PRAGMA foreign_keys = ON;

CREATE TABLE medications (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 120),
    strength TEXT NOT NULL CHECK(length(trim(strength)) BETWEEN 1 AND 80),
    quantity TEXT NOT NULL CHECK(length(trim(quantity)) BETWEEN 1 AND 80),
    daily_times TEXT NOT NULL,
    priority TEXT NOT NULL CHECK(priority IN ('ROUTINE', 'IMPORTANT', 'HIGH')),
    zone_id TEXT NOT NULL,
    created_at TEXT NOT NULL,
    archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0, 1))
);

CREATE TABLE dose_events (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT UNIQUE NOT NULL,
    medication_id TEXT NOT NULL REFERENCES medications(id) ON DELETE RESTRICT,
    scheduled_date TEXT NOT NULL,
    scheduled_time TEXT NOT NULL,
    schedule_zone TEXT NOT NULL,
    action TEXT NOT NULL CHECK(action IN ('UNSURE', 'TAKEN')),
    occurred_at TEXT NOT NULL,
    confirmation_method TEXT NOT NULL CHECK(confirmation_method IN ('USER_CONFIRMED', 'USER_UNSURE')),
    CHECK((action = 'TAKEN' AND confirmation_method = 'USER_CONFIRMED')
       OR (action = 'UNSURE' AND confirmation_method = 'USER_UNSURE'))
);

CREATE UNIQUE INDEX one_taken_event_per_slot
ON dose_events(medication_id, scheduled_date, scheduled_time, schedule_zone)
WHERE action = 'TAKEN';

CREATE INDEX slot_event_lookup
ON dose_events(medication_id, scheduled_date, scheduled_time, schedule_zone, sequence DESC);
