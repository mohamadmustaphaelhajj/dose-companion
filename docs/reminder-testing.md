# Validate reminders before camera development

Reminders are opt-in. Build the app, enable reminders on Today, allow notifications, then use **Allow precise reminders** to grant Android's Alarms & reminders access. The status panel distinguishes disabled notifications, blocked categories and imprecise timing. Reminders and the journal work without internet.

## User acceptance checks

1. Add an example medication with a time two or three minutes in the future. Save its name, strength, quantity and priority.
2. Enable reminders and precise alarms. Return to the Android home screen. A notification should appear at the selected time.
3. Open the notification. Check that the confirmation dialog shows the correct medication and scheduled date/time. Confirming taken must remove the notification and prevent follow-ups for that dose.
4. Schedule another example and use **Snooze 10 min**. The notification should disappear and return after ten minutes, subject to device settings.
5. Use **Unsure** on a notification. It should pause reminders for that occurrence and add an uncertainty entry, never a taken entry. You can resolve it from the journal later.
6. Swipe a notification away. This pauses further reminders for that occurrence without changing the medication history.
7. Disable reminders or archive a medication. Its active notifications and pending repeats must stop. Enabling reminders again does not resurrect dismissed or resolved occurrences.
8. Try a future reminder without internet and after a device restart/first unlock. Check the status panel again after changing Android permissions.

Automatic follow-ups are deliberately bounded:

| Priority | Interval after delivery | Maximum automatic alerts |
| --- | --- | --- |
| Routine | 30 minutes | 2 |
| Important | 15 minutes | 3 |
| Highest | 10 minutes | 4 |

Automatic alerts stop two hours after the scheduled time, or at the next scheduled dose for that medication, whichever is earlier. Explicit snooze requests also respect that cutoff. The history remains unresolved unless the user confirms or marks uncertainty. Notifications describe an awaiting-confirmation record, never instructions to take an additional dose.

## Emulator integration suite

Use the isolated application ID so tests cannot delete or modify the normal journal:

```sh
./gradlew -PreminderSmoke=true :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.dosecompanion.app.ReminderIntegrationTest com.dosecompanion.app.remindertest.test/androidx.test.runner.AndroidJUnitRunner
```

The tests refuse to run against any package without `.remindertest`. They grant permissions only to that package and exercise actual SQLiteOpenHelper migration, notification posting, immutable PendingIntent actions, snooze, cancellation, uncertainty and repeat limits. Notification assertions wait for Android's asynchronous state updates.

For a real AlarmManager process-survival check, run `ReminderSurvivalFixture#prepare` instead of the suite, close the test process with `adb shell am kill com.dosecompanion.app.remindertest`, wait at least 35 seconds, then run `ReminderSurvivalFixture#verifyDeliveredBeforeVerifierStarted`. The fixture schedules an alert 30 seconds ahead; verification requires a notification posted before the verification process started. Repeat without connectivity. Restore the emulator's original network settings afterward.

When finished, build without `-PreminderSmoke=true` to produce the normal app APK.

### Recorded validation — November 19, 2026

- Normal debug build and Android lint passed (no lint errors).
- 34 pure Java domain checks and 11 SQLite/resource checks passed.
- Seven instrumented integration tests passed on the ARM64 API 37 emulator.
- The real 30-second alarm posted while the test process was closed and connectivity disabled, before the verifier process started. Original network settings were restored.
- A real emulator reboot restored the alert. Android deferred the boot delivery; the alert was observed about 174 seconds after reboot. Observe notifications externally before starting instrumentation during boot, since instrumentation can interrupt a pending boot broadcast.
- The normal app was updated in place and the isolated test packages were removed. No new commit was created.

The permission dialog flow and reminder reliability on the friend's physical phone still need user acceptance testing. The automated suite grants permissions to its isolated package to test delivery and actions.

## Platform limits

The app uses local AlarmManager alarms with user-granted exact scheduling access when available. Without that access, it uses inexact alarms and clearly reports possible delays. Android may delay alarms or suppress sound because of Do Not Disturb, battery restrictions, notification settings or manufacturer behavior. Force-stopping the app cancels delivery until the app is reopened; swiping it from Recents is different. Permission revocation can cancel pending exact alarms; reopening the app reconciles schedules. Reboot restoration occurs after the user's first unlock. Test on the target physical phone before depending on reminders for essential medication.

## Keep the next commit limited to reminders

After validation, stage the reminder files and run:

```sh
python3 scripts/reminder_checkpoint.py capture
```

This records the staged Git tree and a binary patch inside `.git`, with no commit or push. Keep later camera changes unstaged, including camera edits to files that already contain staged reminder changes. Before the owner approves the reminder commit:

```sh
python3 scripts/reminder_checkpoint.py verify
git diff --cached --stat
git diff --cached
```

If camera changes were accidentally staged, `python3 scripts/reminder_checkpoint.py restore-index` restores the reminder snapshot in the index while preserving all working files. Commit the verified index only after explicit approval. Do not use `git commit -a`, `git add .`, or an editor's Commit All action for this milestone. The ordinary commit command uses the staged snapshot; camera development remains uncommitted afterward.
