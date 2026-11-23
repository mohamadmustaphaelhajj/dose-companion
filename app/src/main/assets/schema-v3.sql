CREATE TABLE medication_directions (
    medication_id TEXT PRIMARY KEY REFERENCES medications(id),
    confirmed_text TEXT NOT NULL CHECK(length(confirmed_text) <= 2000)
);
