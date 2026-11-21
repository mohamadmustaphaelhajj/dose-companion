package com.dosecompanion.app;

import android.content.Context;
import android.app.NotificationManager;
import android.service.notification.StatusBarNotification;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.dosecompanion.core.DoseSlot;
import com.dosecompanion.core.Medication;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.time.Instant;
import static org.junit.Assert.*;

/** Run separately before killing the test process to check actual AlarmManager delivery. */
@RunWith(AndroidJUnit4.class)
public class ReminderSurvivalFixture {
    @Test public void prepare() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ReminderIntegrationTest.resetIsolated(context);
        DoseSlot slot = ReminderIntegrationTest.fixture(context, Medication.Priority.HIGH);
        try (DoseStore store = new DoseStore(context)) {
            store.updateReminder(slot, Instant.now().plusSeconds(30), 0);
        }
        ReminderEngine.reconcile(context);
        assertTrue(ReminderEngine.exactAllowed(context));
        assertTrue(ReminderEngine.notificationsAllowed(context));
    }

    @Test public void verifyDeliveredBeforeVerifierStarted() {
        long verifierStarted = System.currentTimeMillis();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getPackageName().endsWith(".remindertest"));
        StatusBarNotification[] notifications = context.getSystemService(NotificationManager.class).getActiveNotifications();
        assertEquals("Expected the actual OS alarm to post while the process was closed", 1, notifications.length);
        assertTrue("Notification must predate this verification process", notifications[0].getPostTime() < verifierStarted - 1000);
        try (DoseStore store = new DoseStore(context)) {
            assertTrue(store.reminders().stream().anyMatch(entry -> entry.deliveries == 1));
            assertTrue("An alert must never mark a medication taken", store.history().isEmpty());
        }
        ReminderEngine.setEnabled(context, false);
        ReminderEngine.reconcile(context);
    }
}
