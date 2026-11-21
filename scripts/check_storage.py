"""Exercise the application's real SQLite schema without Android dependencies."""

from pathlib import Path
import sqlite3
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "app/src/main/assets/schema.sql"


class StorageChecks(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript(SCHEMA.read_text())
        self.db.executescript((SCHEMA.parent / "schema-v2.sql").read_text())
        self.db.execute(
            "INSERT INTO medications VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ("med-1", "Example medication", "10 mg", "1 tablet", "12:00,20:00",
             "HIGH", "America/Toronto", "2026-11-19T13:00:00Z", 0),
        )

    def tearDown(self):
        self.db.close()

    def event(self, event_id="event-1", action="TAKEN", date="2026-11-19", time="12:00",
              medication="med-1", method=None):
        if method is None:
            method = "USER_CONFIRMED" if action == "TAKEN" else "USER_UNSURE"
        self.db.execute(
            "INSERT INTO dose_events (event_id, medication_id, scheduled_date, scheduled_time, "
            "schedule_zone, action, occurred_at, confirmation_method) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (event_id, medication, date, time, "America/Toronto", action,
             "2026-11-19T17:05:00Z", method),
        )

    def test_duplicate_confirmation_is_rejected(self):
        self.event()
        with self.assertRaises(sqlite3.IntegrityError):
            self.event(event_id="event-2")

    def test_uncertainty_is_preserved_after_confirmation(self):
        self.event(event_id="uncertain", action="UNSURE")
        self.event(event_id="confirmed")
        self.assertEqual(self.db.execute("SELECT action FROM dose_events ORDER BY sequence").fetchall(),
                         [("UNSURE",), ("TAKEN",)])

    def test_distinct_doses_can_be_confirmed(self):
        self.event()
        self.event(event_id="evening", time="20:00")
        self.event(event_id="tomorrow", date="2026-11-20")
        self.assertEqual(self.db.execute("SELECT count(*) FROM dose_events").fetchone()[0], 3)

    def test_unknown_medication_cannot_be_logged(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.event(medication="missing")

    def test_medication_deletion_cannot_erase_history(self):
        self.event()
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("DELETE FROM medications WHERE id = 'med-1'")

    def test_archiving_preserves_history(self):
        self.event()
        self.db.execute("UPDATE medications SET archived = 1 WHERE id = 'med-1'")
        self.assertEqual(self.db.execute("SELECT count(*) FROM dose_events").fetchone()[0], 1)

    def test_status_and_confirmation_method_must_agree(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.event(action="TAKEN", method="USER_UNSURE")

    def test_unknown_confirmation_method_is_rejected(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.event(method="CV_VERIFIED")

    def test_history_survives_database_reopen(self):
        # Backup the actual SQLite database to disk and reopen it, rather than testing a mock store.
        import tempfile
        self.event()
        self.db.commit()
        with tempfile.TemporaryDirectory(prefix="dose-storage-") as directory:
            path = Path(directory) / "journal.db"
            with sqlite3.connect(path) as target:
                self.db.backup(target)
            with sqlite3.connect(path) as reopened:
                self.assertEqual(reopened.execute("SELECT action FROM dose_events").fetchone()[0], "TAKEN")

    def test_v2_upgrade_preserves_v1_history(self):
        legacy = sqlite3.connect(":memory:")
        try:
            legacy.executescript(SCHEMA.read_text())
            legacy.execute("INSERT INTO medications VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                           ("legacy", "Example", "1 mg", "1 tablet", "12:00", "HIGH", "UTC",
                            "2026-11-19T00:00:00Z", 0))
            legacy.execute("INSERT INTO dose_events (event_id, medication_id, scheduled_date, "
                           "scheduled_time, schedule_zone, action, occurred_at, confirmation_method) "
                           "VALUES ('old', 'legacy', '2026-11-19', '12:00', 'UTC', 'TAKEN', "
                           "'2026-11-19T12:00:00Z', 'USER_CONFIRMED')")
            legacy.executescript((SCHEMA.parent / "schema-v2.sql").read_text())
            self.assertEqual(legacy.execute("SELECT action FROM dose_events").fetchone()[0], "TAKEN")
            self.assertEqual(legacy.execute("SELECT count(*) FROM reminder_queue").fetchone()[0], 0)
        finally:
            legacy.close()


class AndroidResourcesChecks(unittest.TestCase):
    def test_all_resource_xml_is_well_formed(self):
        for path in (ROOT / "app/src/main").rglob("*.xml"):
            with self.subTest(path=str(path.relative_to(ROOT))):
                ET.parse(path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
