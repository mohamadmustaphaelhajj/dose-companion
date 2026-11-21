# Dose Companion

A medication journal for Android, with a planned Windows web companion. Designed to help people check what they confirmed taking and when.

**Status: development preview, milestone 2.** Opt-in offline Android reminders are implemented alongside the local journal. Camera processing, accounts and synchronization are not implemented. Emulator checks cover notification actions and migration; testing on the target physical phone remains necessary before depending on reminders for essential medication.

## Implemented in this milestone

- Add a medication's name, strength, quantity per dose, daily times, and reminder priority.
- See today's scheduled doses, in chronological order.
- Explicitly confirm a dose as taken, or record uncertainty without marking it taken.
- Review history with the scheduled date/time and the actual confirmation timestamp.
- Archive a schedule without deleting its history.
- Save everything in an app-private SQLite database, with no network or camera permissions.
- Reject duplicate taken entries for the same scheduled dose, including at the database level.
- Enable local reminders, snooze, priority-based follow-ups and recovery after restart/time changes.
- See notification/alarm permission status and stop reminders by resolving, dismissing or archiving a dose.
- Upgrade the database without losing existing medication history.

The journal contains user confirmations, not medical verification of ingestion. Priority controls a bounded reminder cadence, never medication amounts or instructions about taking an additional dose.

## Development

The native Android interface uses Java and Android framework views. Domain rules live in a separate Java module so scheduling and confirmation behavior can be tested without an Android device. The app has no third-party runtime dependencies; AndroidX Test dependencies are used only by instrumentation tests.

### Run core and storage checks

Requires JDK 17 or newer and Python 3:

```sh
bash scripts/check.sh
```

Checks cover scheduling around enrollment, midnight, time zones and daylight saving; explicit confirmation and repeated actions; and the actual SQLite schema's persistence, uniqueness and history-preservation constraints. A separate isolated emulator suite exercises notifications and Android's SQLiteOpenHelper lifecycle; see [reminder testing](docs/reminder-testing.md).

Verified on November 19, 2026: 34 core checks, 11 SQLite/resource checks and 7 emulator integration tests passed. The normal APK build and lint passed using JDK 21. A real local alarm was delivered with the test process closed and emulator connectivity disabled; the network settings were restored afterward. Reboot recovery also passed, with an observed startup/delivery delay of about three minutes on the API 37 emulator. Physical-phone testing remains outstanding.

### Build Android

Requires Android Studio with JDK 17 or 21, Android SDK platform 35, and its supported build tools. The project uses Android Gradle Plugin 8.13.0 and Gradle 8.13. Minimum supported device: Android 8 (API 26).

Open this directory in Android Studio and select the `app` run configuration. The official Gradle wrapper is included, with its distribution checksum pinned. From a terminal with the Android SDK configured:

```sh
./gradlew :core:verifyCore :app:assembleDebug :app:lintDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`. The first build downloads Gradle and build dependencies; the installed app's local journal does not need internet. A debug APK will appear at `app/build/outputs/apk/debug/app-debug.apk` after a successful build.

Do not check `local.properties`, signing keys, personal labels, medication records, credentials, or captured media into GitHub.

## Schedule behavior

- Daily schedules retain the device's time zone at creation. Travel does not silently move the schedule to another time zone.
- Doses before schedule enrollment are not created retroactively.
- Each scheduled date and time has one identity, even during a repeated daylight-saving hour. Nonexistent spring-forward times move forward through the gap. These semantics will need review before reminder release.
- Confirming ahead of the scheduled time requires an explicit dialog; the actual confirmation time is recorded separately.
- Unsure entries remain in the history when a later confirmation resolves the dose.
- Uninstalling the app removes local history. Device/cloud backup is disabled in this preview. Export and account synchronization are later milestones.

## Next milestones

1. **Bottle label matching:** on-device OCR of the name and strength, compared with the user-entered schedule; stable readings across frames and clear handling of uncertainty.
2. **Guided action tracking:** hand and face landmarks plus a temporal sequence detector for hand-to-mouth gestures. Gesture detection does not establish pill ingestion or swallowing. The user explicitly confirms completion.
3. **Account and Windows companion:** authenticated access, history sync, offline queues and visible sync freshness, including conflicting offline entries for the same dose.
4. **Portfolio demonstration:** optional CV overlays and measured recognition/gesture performance on consented or synthetic development data. The app will not persist users' camera frames.

Reminder setup, limits, validation and the reminder-only staging checkpoint are documented in [reminder testing](docs/reminder-testing.md).

See [the project decisions](docs/project-decisions.md) for the intended camera flow and privacy rules.

## Version control

Working name: `dose-companion`. Intended visibility: public. No repository or commit is created automatically. Every commit requires the project owner's explicit approval after reviewing the proposed changes.
