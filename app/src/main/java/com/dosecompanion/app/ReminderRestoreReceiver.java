package com.dosecompanion.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class ReminderRestoreReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !Intent.ACTION_TIME_CHANGED.equals(action) && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                && !"android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action)) return;
        PendingResult result = goAsync();
        ReminderEngine.WORKER.execute(() -> {
            try { ReminderEngine.reconcile(context); }
            catch (RuntimeException error) { Log.e("DoseReminders", "Reminder restore failed; reopen the app."); }
            finally { result.finish(); }
        });
    }
}
