package com.dosecompanion.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.dosecompanion.core.DoseRules;
import com.dosecompanion.core.DoseSlot;
import com.dosecompanion.core.Medication;
import com.dosecompanion.core.ReminderPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** App-private database. Every confirmation and uncertainty entry is retained. */
final class DoseStore extends SQLiteOpenHelper {
    private final Context context;

    DoseStore(Context context) {
        super(context, "dose-companion.db", null, 3);
        this.context = context.getApplicationContext();
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        executeAsset(db, "schema.sql");
        executeAsset(db, "schema-v2.sql");
        executeAsset(db, "schema-v3.sql");
    }

    private void executeAsset(SQLiteDatabase db, String asset) {
        try (InputStream input = context.getAssets().open(asset)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            String schema = new String(output.toByteArray(), StandardCharsets.UTF_8);
            for (String statement : schema.split(";")) {
                // Foreign keys are configured before SQLiteOpenHelper's creation transaction.
                if (!statement.trim().isEmpty() && !statement.trim().startsWith("PRAGMA")) {
                    db.execSQL(statement);
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to initialize local storage.", error);
        }
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1 || newVersion != 3) throw new IllegalStateException("A database migration is required.");
        if (oldVersion < 2) executeAsset(db, "schema-v2.sql");
        if (oldVersion < 3) executeAsset(db, "schema-v3.sql");
    }

    String directions(String medicationId) {
        try (Cursor cursor = getReadableDatabase().query("medication_directions", new String[]{"confirmed_text"},
                "medication_id = ?", new String[]{medicationId}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        }
    }
    void saveDirections(String medicationId, String text) {
        if (text == null || text.trim().isEmpty() || text.length() > 2000) throw new IllegalArgumentException("Review the directions before saving.");
        ContentValues values = new ContentValues(); values.put("medication_id", medicationId);
        values.put("confirmed_text", text.trim());
        getWritableDatabase().insertWithOnConflict("medication_directions", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }
    void add(Medication medication, String directions) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { add(medication); if (!directions.trim().isEmpty()) saveDirections(medication.id, directions);
            db.setTransactionSuccessful(); } finally { db.endTransaction(); }
    }

    List<Medication> medications() {
        List<Medication> medications = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("medications", null, null,
                null, null, null, "name COLLATE NOCASE")) {
            while (cursor.moveToNext()) {
                List<LocalTime> times = new ArrayList<>();
                for (String time : value(cursor, "daily_times").split(",")) {
                    times.add(LocalTime.parse(time));
                }
                medications.add(new Medication(value(cursor, "id"), value(cursor, "name"),
                        value(cursor, "strength"), value(cursor, "quantity"), times,
                        Medication.Priority.valueOf(value(cursor, "priority")),
                        ZoneId.of(value(cursor, "zone_id")), Instant.parse(value(cursor, "created_at")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("archived")) == 1));
            }
        }
        return medications;
    }

    void add(Medication medication) {
        ContentValues values = new ContentValues();
        values.put("id", medication.id);
        values.put("name", medication.name);
        values.put("strength", medication.strength);
        values.put("quantity", medication.quantity);
        values.put("daily_times", medication.times.stream().map(LocalTime::toString)
                .collect(Collectors.joining(",")));
        values.put("priority", medication.priority.name());
        values.put("zone_id", medication.zone.getId());
        values.put("created_at", medication.createdAt.toString());
        getWritableDatabase().insertOrThrow("medications", null, values);
    }

    void archive(Medication medication) {
        ContentValues values = new ContentValues();
        values.put("archived", 1);
        getWritableDatabase().update("medications", values, "id = ?", new String[]{medication.id});
    }

    DoseRules.Status status(DoseSlot slot) {
        return status(getReadableDatabase(), slot);
    }

    private DoseRules.Status status(SQLiteDatabase db, DoseSlot slot) {
        try (Cursor cursor = db.query("dose_events", new String[]{"action"},
                "medication_id = ? AND scheduled_date = ? AND scheduled_time = ? AND schedule_zone = ?",
                slotArgs(slot), null, null,
                "CASE WHEN action = 'TAKEN' THEN 0 ELSE 1 END, sequence DESC", "1")) {
            return cursor.moveToFirst()
                    ? DoseRules.Status.valueOf(cursor.getString(0)) : DoseRules.Status.PENDING;
        }
    }

    void record(DoseSlot slot, DoseRules.Status requested, boolean userConfirmed, Instant now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor medication = db.query("medications", new String[]{"archived"}, "id = ?",
                    new String[]{slot.medication.id}, null, null, null)) {
                if (!medication.moveToFirst() || medication.getInt(0) == 1) {
                    throw new IllegalStateException("This medication schedule is no longer active.");
                }
            }
            DoseRules.transition(status(db, slot), requested, userConfirmed);
            ContentValues values = new ContentValues();
            values.put("event_id", UUID.randomUUID().toString());
            values.put("medication_id", slot.medication.id);
            values.put("scheduled_date", slot.date.toString());
            values.put("scheduled_time", slot.time.toString());
            values.put("schedule_zone", slot.medication.zone.getId());
            values.put("action", requested.name());
            values.put("occurred_at", now.toString());
            values.put("confirmation_method", requested == DoseRules.Status.TAKEN
                    ? "USER_CONFIRMED" : "USER_UNSURE");
            db.insertOrThrow("dose_events", null, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    List<HistoryEntry> history() {
        List<HistoryEntry> entries = new ArrayList<>();
        String sql = "SELECT e.*, m.name, m.strength, m.quantity FROM dose_events e "
                + "JOIN medications m ON m.id = e.medication_id ORDER BY e.sequence DESC";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                entries.add(new HistoryEntry(value(cursor, "medication_id"), value(cursor, "name"), value(cursor, "strength"),
                        value(cursor, "quantity"), value(cursor, "scheduled_date"),
                        value(cursor, "scheduled_time"), ZoneId.of(value(cursor, "schedule_zone")),
                        Instant.parse(value(cursor, "occurred_at")),
                        DoseRules.Status.valueOf(value(cursor, "action"))));
            }
        }
        return entries;
    }

    private String[] slotArgs(DoseSlot slot) {
        return new String[]{slot.medication.id, slot.date.toString(), slot.time.toString(),
                slot.medication.zone.getId()};
    }

    private static String value(Cursor cursor, String name) {
        return cursor.getString(cursor.getColumnIndexOrThrow(name));
    }

    DoseSlot findSlot(String medicationId, String date, String time, String zone) {
        for (Medication medication : medications()) {
            if (medication.id.equals(medicationId) && medication.zone.getId().equals(zone)) {
                return new DoseSlot(medication, LocalDate.parse(date), LocalTime.parse(time));
            }
        }
        return null;
    }

    void ensureReminder(DoseSlot slot) {
        ContentValues values = new ContentValues();
        values.put("slot_key", slot.key());
        values.put("medication_id", slot.medication.id);
        values.put("scheduled_date", slot.date.toString());
        values.put("scheduled_time", slot.time.toString());
        values.put("schedule_zone", slot.medication.zone.getId());
        values.put("next_at", slot.scheduledAt().toEpochMilli());
        values.put("expires_at", ReminderPolicy.expiresAt(slot).toEpochMilli());
        getWritableDatabase().insertWithOnConflict("reminder_queue", null, values,
                SQLiteDatabase.CONFLICT_IGNORE);
    }

    List<ReminderEntry> reminders() {
        List<ReminderEntry> entries = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("reminder_queue", null, null,
                null, null, null, "next_at ASC")) {
            while (cursor.moveToNext()) {
                DoseSlot slot = findSlot(value(cursor, "medication_id"), value(cursor, "scheduled_date"),
                        value(cursor, "scheduled_time"), value(cursor, "schedule_zone"));
                if (slot == null) continue;
                int nextColumn = cursor.getColumnIndexOrThrow("next_at");
                entries.add(new ReminderEntry(slot, cursor.isNull(nextColumn) ? null
                        : Instant.ofEpochMilli(cursor.getLong(nextColumn)),
                        Instant.ofEpochMilli(cursor.getLong(cursor.getColumnIndexOrThrow("expires_at"))),
                        cursor.getInt(cursor.getColumnIndexOrThrow("deliveries"))));
            }
        }
        return entries;
    }

    void updateReminder(DoseSlot slot, Instant next, int deliveries) {
        ContentValues values = new ContentValues();
        if (next == null) values.putNull("next_at");
        else values.put("next_at", next.toEpochMilli());
        values.put("deliveries", deliveries);
        getWritableDatabase().update("reminder_queue", values, "slot_key = ?", new String[]{slot.key()});
    }

    void pruneReminders(Instant now) {
        getWritableDatabase().delete("reminder_queue", "expires_at < ?",
                new String[]{Long.toString(now.minusSeconds(172800).toEpochMilli())});
    }

    static final class ReminderEntry {
        final DoseSlot slot;
        final Instant next, expires;
        final int deliveries;
        ReminderEntry(DoseSlot slot, Instant next, Instant expires, int deliveries) {
            this.slot = slot;
            this.next = next;
            this.expires = expires;
            this.deliveries = deliveries;
        }
    }

    static final class HistoryEntry {
        final String medicationId, name, strength, quantity, scheduledDate, scheduledTime;
        final ZoneId zone;
        final Instant recordedAt;
        final DoseRules.Status status;

        HistoryEntry(String medicationId, String name, String strength, String quantity, String scheduledDate,
                     String scheduledTime, ZoneId zone, Instant recordedAt, DoseRules.Status status) {
            this.medicationId = medicationId;
            this.name = name;
            this.strength = strength;
            this.quantity = quantity;
            this.scheduledDate = scheduledDate;
            this.scheduledTime = scheduledTime;
            this.zone = zone;
            this.recordedAt = recordedAt;
            this.status = status;
        }
    }
}
