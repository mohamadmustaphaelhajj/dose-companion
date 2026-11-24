package com.dosecompanion.app;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.service.notification.StatusBarNotification;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.dosecompanion.core.DoseRules;
import com.dosecompanion.core.DoseSlot;
import com.dosecompanion.core.Medication;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReminderIntegrationTest {
    Context context;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        resetIsolated(context);
    }

    static void resetIsolated(Context context) throws Exception {
        assertTrue("Use -PreminderSmoke=true; never run against the user's journal",
                context.getPackageName().endsWith(".remindertest"));
        ReminderEngine.setEnabled(context, false);
        ReminderEngine.reconcile(context);
        long deadline = System.currentTimeMillis() + 5000;
        while (context.getSystemService(NotificationManager.class).getActiveNotifications().length != 0
                && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(0, context.getSystemService(NotificationManager.class).getActiveNotifications().length);
        context.deleteDatabase("dose-companion.db");
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant " + context.getPackageName()
                + " android.permission.POST_NOTIFICATIONS");
        if (Build.VERSION.SDK_INT >= 31) shell("appops set " + context.getPackageName()
                + " SCHEDULE_EXACT_ALARM allow");
        ReminderEngine.setEnabled(context, true);
        assertTrue(ReminderEngine.notificationsAllowed(context));
    }

    static DoseSlot fixture(Context context, Medication.Priority priority) {
        Instant due = Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(60);
        Medication medication = new Medication("smoke-medication", "Reminder smoke test", "Example strength",
                "Example quantity", Collections.singletonList(due.atZone(ZoneId.of("UTC")).toLocalTime()),
                priority, ZoneId.of("UTC"), due.minusSeconds(60), false);
        DoseSlot slot = new DoseSlot(medication, due.atZone(medication.zone).toLocalDate(), medication.times.get(0));
        try (DoseStore store = new DoseStore(context)) { store.add(medication); store.ensureReminder(slot); }
        return slot;
    }

    @Test public void dueNotificationRequiresExplicitConfirmation() throws Exception {
        DoseSlot slot = fixture(context, Medication.Priority.HIGH);
        ReminderEngine.process(context, true, Instant.now());
        await(() -> notification(slot) != null);
        assertEquals(3, notification(slot).getNotification().actions.length);
        try (DoseStore store = new DoseStore(context)) {
            assertEquals(DoseRules.Status.PENDING, store.status(slot));
            assertTrue(store.history().isEmpty());
        }
    }

    @Test public void confirmationCancelsNotificationAndRepeats() throws Exception {
        DoseSlot slot = fixture(context, Medication.Priority.HIGH);
        ReminderEngine.process(context, true, Instant.now());
        await(() -> notification(slot) != null);
        try (DoseStore store = new DoseStore(context)) {
            store.record(slot, DoseRules.Status.TAKEN, true, Instant.now());
        }
        ReminderEngine.reconcile(context);
        await(() -> notification(slot) == null);
        assertNull(entry(slot).next);
        ReminderEngine.process(context, true, Instant.now().plusSeconds(1800));
        assertNull(notification(slot));
    }

    @Test public void unsureNotificationActionPausesWithoutRecordingTaken() throws Exception {
        DoseSlot slot = fixture(context, Medication.Priority.HIGH);
        ReminderEngine.process(context, true, Instant.now());
        await(() -> notification(slot) != null);
        notification(slot).getNotification().actions[2].actionIntent.send();
        await(() -> {
            try (DoseStore store = new DoseStore(context)) { return store.status(slot) == DoseRules.Status.UNSURE; }
        });
        await(() -> notification(slot) == null);
        assertNull(entry(slot).next);
        try (DoseStore store = new DoseStore(context)) {
            assertEquals(1, store.history().size());
            assertEquals(DoseRules.Status.UNSURE, store.history().get(0).status);
        }
    }

    @Test public void snoozeAndSwipeDismissAreDurable() throws Exception {
        DoseSlot slot = fixture(context, Medication.Priority.HIGH);
        ReminderEngine.process(context, true, Instant.now());
        await(() -> notification(slot) != null);
        Instant before = Instant.now();
        notification(slot).getNotification().actions[1].actionIntent.send();
        await(() -> notification(slot) == null);
        assertTrue(entry(slot).next.isAfter(before.plusSeconds(590)));
        ReminderEngine.handleAction(context, ReminderEngine.forSlot(
                new Intent().setAction(ReminderEngine.DISMISS), slot));
        ReminderEngine.reconcile(context);
        assertNull(entry(slot).next);
        try (DoseStore store = new DoseStore(context)) { assertTrue(store.history().isEmpty()); }
    }

    @Test public void highestPriorityEscalationStopsAtFourDeliveries() throws Exception {
        DoseSlot slot = fixture(context, Medication.Priority.HIGH);
        Instant now = Instant.now();
        for (int index = 0; index < 4; index++) ReminderEngine.process(context, true, now.plusSeconds(index * 600));
        assertEquals(4, entry(slot).deliveries);
        assertNull(entry(slot).next);
        ReminderEngine.process(context, true, now.plusSeconds(3000));
        assertEquals(4, entry(slot).deliveries);
        ReminderEngine.process(context, true, slot.scheduledAt().plusSeconds(7200));
        await(() -> notification(slot) == null);
    }

    @Test public void archiveAndDisableCancelAlerts() throws Exception {
        DoseSlot slot = fixture(context, Medication.Priority.HIGH);
        ReminderEngine.process(context, true, Instant.now());
        await(() -> notification(slot) != null);
        ReminderEngine.setEnabled(context, false);
        ReminderEngine.reconcile(context);
        await(() -> notification(slot) == null);
        ReminderEngine.setEnabled(context, true);
        try (DoseStore store = new DoseStore(context)) { store.archive(slot.medication); }
        ReminderEngine.process(context, true, Instant.now().plusSeconds(600));
        assertNull(notification(slot));
        assertNull(entry(slot).next);
    }

    @Test public void actualSQLiteOpenHelperUpgradePreservesJournal() throws Exception {
        try (SQLiteDatabase db = context.openOrCreateDatabase("dose-companion.db", Context.MODE_PRIVATE, null)) {
            String schema;
            try (InputStream input = context.getAssets().open("schema.sql")) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                schema = new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
            for (String statement : schema.split(";")) if (!statement.trim().isEmpty()) db.execSQL(statement);
            db.execSQL("INSERT INTO medications VALUES ('legacy', 'Legacy example', '10 mg', '1 tablet', "
                    + "'12:00', 'ROUTINE', 'UTC', '2026-01-01T00:00:00Z', 0)");
            db.execSQL("INSERT INTO dose_events (event_id, medication_id, scheduled_date, scheduled_time, "
                    + "schedule_zone, action, occurred_at, confirmation_method) VALUES ('old', 'legacy', "
                    + "'2026-11-19', '12:00', 'UTC', 'TAKEN', '2026-11-19T12:00:00Z', 'USER_CONFIRMED')");
            db.setVersion(1);
        }
        try (DoseStore store = new DoseStore(context)) {
            assertEquals(1, store.history().size());
            assertEquals(DoseRules.Status.TAKEN, store.history().get(0).status);
            assertEquals(3, store.getReadableDatabase().getVersion());
            assertTrue(store.reminders().isEmpty());
        }
    }

    @Test public void v2UpgradePreservesHistoryAndStoresReviewedDirections() throws Exception {
        DoseSlot slot = fixture(context, Medication.Priority.HIGH);
        try (DoseStore store = new DoseStore(context)) {
            store.record(slot, DoseRules.Status.UNSURE, false, Instant.now());
            SQLiteDatabase db = store.getWritableDatabase();
            db.execSQL("DROP TABLE medication_directions"); db.setVersion(2);
        }
        try (DoseStore store = new DoseStore(context)) {
            assertEquals(3, store.getReadableDatabase().getVersion());
            assertEquals(1, store.history().size()); assertEquals(DoseRules.Status.UNSURE, store.status(slot));
            assertEquals("", store.directions(slot.medication.id));
            store.saveDirections(slot.medication.id, "Take 1 tablet daily");
        }
        try (DoseStore store = new DoseStore(context)) {
            assertEquals("Take 1 tablet daily", store.directions(slot.medication.id));
            assertEquals(1, store.history().size());
        }
    }

    private StatusBarNotification notification(DoseSlot slot) {
        for (StatusBarNotification notification : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (slot.key().equals(notification.getTag())) return notification;
        }
        return null;
    }

    private DoseStore.ReminderEntry entry(DoseSlot slot) {
        try (DoseStore store = new DoseStore(context)) {
            for (DoseStore.ReminderEntry entry : store.reminders()) if (entry.slot.key().equals(slot.key())) return entry;
        }
        throw new AssertionError("Missing reminder queue entry");
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertTrue(condition.getAsBoolean());
    }

    static void shell(String command) throws Exception {
        try (ParcelFileDescriptor output = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(output)) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) { /* Drain so the shell command completes. */ }
        }
    }
}
