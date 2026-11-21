package com.dosecompanion.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import com.dosecompanion.core.DoseRules;
import com.dosecompanion.core.DoseSlot;
import com.dosecompanion.core.Medication;
import com.dosecompanion.core.ReminderPolicy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Durable local scheduling; one OS alarm points at the next outstanding queue entry. */
final class ReminderEngine {
    static final String TICK = "com.dosecompanion.TICK";
    static final String SNOOZE = "com.dosecompanion.SNOOZE";
    static final String UNSURE = "com.dosecompanion.UNSURE";
    static final String DISMISS = "com.dosecompanion.DISMISS";
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();
    private static final String PREFS = "reminders";

    private ReminderEngine() {}

    static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).commit();
    }

    static boolean notificationsAllowed(Context context) {
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED)
                && context.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    static boolean exactAllowed(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static void createChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        for (Medication.Priority priority : Medication.Priority.values()) {
            NotificationChannel channel = new NotificationChannel(channelId(priority),
                    priority.label + " medication reminders", priority == Medication.Priority.ROUTINE
                    ? NotificationManager.IMPORTANCE_DEFAULT : NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Reminders about a scheduled dose awaiting your confirmation.");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            manager.createNotificationChannel(channel);
        }
    }

    static boolean channelsAllowed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        for (Medication.Priority priority : Medication.Priority.values()) {
            NotificationChannel channel = manager.getNotificationChannel(channelId(priority));
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }

    static void reconcile(Context context) { process(context, false, Instant.now()); }

    static void process(Context context, boolean deliverDue, Instant now) {
        synchronized (LOCK) {
            createChannels(context);
            AlarmManager alarms = context.getSystemService(AlarmManager.class);
            NotificationManager notifications = context.getSystemService(NotificationManager.class);
            if (!enabled(context) || !notificationsAllowed(context)) {
                alarms.cancel(tickIntent(context));
                notifications.cancelAll();
                return;
            }
            try (DoseStore store = new DoseStore(context)) {
                store.pruneReminders(now);
                for (Medication medication : store.medications()) {
                    if (medication.archived) continue;
                    LocalDate date = now.atZone(medication.zone).toLocalDate();
                    for (int day = 0; day <= 1; day++) {
                        for (LocalTime time : medication.times) {
                            DoseSlot slot = new DoseSlot(medication, date.plusDays(day), time);
                            if (!slot.scheduledAt().isBefore(medication.createdAt)) store.ensureReminder(slot);
                        }
                    }
                }
                Instant earliest = null;
                for (DoseStore.ReminderEntry entry : store.reminders()) {
                    DoseSlot slot = entry.slot;
                    if (slot.medication.archived || store.status(slot) != DoseRules.Status.PENDING
                            || !now.isBefore(entry.expires)) {
                        store.updateReminder(slot, null, entry.deliveries);
                        notifications.cancel(slot.key(), 0);
                        continue;
                    }
                    Instant next = entry.next;
                    if (next != null && !next.isAfter(now) && deliverDue) {
                        int delivered = entry.deliveries + 1;
                        next = ReminderPolicy.afterDelivery(slot.medication.priority, delivered, now, entry.expires);
                        store.updateReminder(slot, next, delivered);
                        showNotification(context, slot, delivered);
                    }
                    if (next != null && (earliest == null || next.isBefore(earliest))) earliest = next;
                    if (entry.deliveries > 0 && (earliest == null || entry.expires.isBefore(earliest))) {
                        earliest = entry.expires;
                    }
                }
                // A daily maintenance tick rolls tomorrow's schedules forward, including DST changes.
                Instant maintenance = now.plusSeconds(12 * 3600);
                Instant trigger = earliest == null || maintenance.isBefore(earliest) ? maintenance : earliest;
                schedule(context, trigger.isAfter(now) ? trigger : now.plusMillis(500));
            }
        }
    }

    static void handleAction(Context context, Intent intent) {
        synchronized (LOCK) {
            try (DoseStore store = new DoseStore(context)) {
                DoseSlot slot = slotFromIntent(store, intent);
                if (slot == null) return;
                Instant now = Instant.now();
                for (DoseStore.ReminderEntry entry : store.reminders()) {
                    if (!entry.slot.key().equals(slot.key())) continue;
                    if (UNSURE.equals(intent.getAction()) && store.status(slot) == DoseRules.Status.PENDING
                            && !slot.medication.archived) {
                        store.record(slot, DoseRules.Status.UNSURE, false, now);
                    }
                    Instant next = SNOOZE.equals(intent.getAction())
                            && store.status(slot) == DoseRules.Status.PENDING && !slot.medication.archived
                            ? ReminderPolicy.snooze(now, entry.expires) : null;
                    store.updateReminder(slot, next, entry.deliveries);
                    context.getSystemService(NotificationManager.class).cancel(slot.key(), 0);
                    break;
                }
            }
            reconcile(context);
        }
    }

    static DoseSlot slotFromIntent(DoseStore store, Intent intent) {
        String id = intent.getStringExtra("medication_id");
        String date = intent.getStringExtra("scheduled_date");
        String time = intent.getStringExtra("scheduled_time");
        String zone = intent.getStringExtra("schedule_zone");
        if (id == null || date == null || time == null || zone == null) return null;
        try { return store.findSlot(id, date, time, zone); }
        catch (IllegalArgumentException error) { return null; }
    }

    static Intent forSlot(Intent intent, DoseSlot slot) {
        return intent.setData(Uri.fromParts("dosecompanion", slot.key(), null))
                .putExtra("medication_id", slot.medication.id)
                .putExtra("scheduled_date", slot.date.toString())
                .putExtra("scheduled_time", slot.time.toString())
                .putExtra("schedule_zone", slot.medication.zone.getId());
    }

    private static PendingIntent tickIntent(Context context) {
        return PendingIntent.getBroadcast(context, 0, new Intent(context, ReminderReceiver.class).setAction(TICK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void schedule(Context context, Instant trigger) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        try {
            if (exactAllowed(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), tickIntent(context));
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), tickIntent(context));
            }
        } catch (SecurityException revoked) {
            // The user can revoke exact alarm access between the capability check and scheduling.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), tickIntent(context));
        }
    }

    private static PendingIntent action(Context context, DoseSlot slot, String action) {
        return PendingIntent.getBroadcast(context, 0, forSlot(new Intent(context, ReminderReceiver.class)
                .setAction(action), slot), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void showNotification(Context context, DoseSlot slot, int delivered) {
        Intent open = forSlot(new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), slot);
        PendingIntent openDose = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String time = DateTimeFormatter.ofPattern("HH:mm").format(slot.time);
        String description = "Scheduled " + time + " · Awaiting your confirmation";
        Notification publicVersion = new Notification.Builder(context, channelId(slot.medication.priority))
                .setSmallIcon(com.dosecompanion.app.R.drawable.ic_notification)
                .setContentTitle("Dose Companion").setContentText("A scheduled dose needs your attention.").build();
        Notification notification = new Notification.Builder(context, channelId(slot.medication.priority))
                .setSmallIcon(com.dosecompanion.app.R.drawable.ic_notification)
                .setContentTitle(slot.medication.name)
                .setContentText(description)
                .setStyle(new Notification.BigTextStyle().bigText(description + "\n"
                        + slot.medication.strength + " · " + slot.medication.quantity
                        + "\nOpen the record to confirm. Swipe away to pause this dose's reminders."))
                .setSubText(delivered == 1 ? "Scheduled dose" : "Follow-up " + (delivered - 1))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
                .setContentIntent(openDose).setAutoCancel(false)
                .setDeleteIntent(action(context, slot, DISMISS))
                .addAction(new Notification.Action.Builder(null, "Open dose", openDose).build())
                .addAction(new Notification.Action.Builder(null, "Snooze 10 min", action(context, slot, SNOOZE)).build())
                .addAction(new Notification.Action.Builder(null, "Unsure", action(context, slot, UNSURE)).build())
                .build();
        try { context.getSystemService(NotificationManager.class).notify(slot.key(), 0, notification); }
        catch (SecurityException revoked) { /* Permission changed while the alarm was being processed. */ }
    }

    private static String channelId(Medication.Priority priority) { return "dose_" + priority.name(); }
}
