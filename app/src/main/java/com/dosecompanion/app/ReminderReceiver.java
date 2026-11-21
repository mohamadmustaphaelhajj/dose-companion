package com.dosecompanion.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.time.Instant;

public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        PendingResult result = goAsync();
        ReminderEngine.WORKER.execute(() -> {
            try {
                if (ReminderEngine.TICK.equals(intent.getAction())) {
                    ReminderEngine.process(context, true, Instant.now());
                } else if (ReminderEngine.SNOOZE.equals(intent.getAction())
                        || ReminderEngine.UNSURE.equals(intent.getAction())
                        || ReminderEngine.DISMISS.equals(intent.getAction())) {
                    ReminderEngine.handleAction(context, intent);
                }
            } catch (RuntimeException error) {
                Log.e("DoseReminders", "Reminder processing failed; reopen the app to reschedule.");
            } finally { result.finish(); }
        });
    }
}
