<<<<<<< HEAD
# dose-companion
=======
# Dose Companion

A medication journal for Android, with a planned Windows web companion. Designed to help people check what they confirmed taking and when.

**Status: development preview, milestone 1.** The debug APK builds successfully and has been installed and launched on an ARM64 Android API 37 emulator. The initial screen was visually checked; full workflow and physical-device testing remain. This preview has no reminders, camera processing, accounts, or synchronization. It must not be relied on for essential reminders.

## Implemented in this milestone

- Add a medication's name, strength, quantity per dose, daily times, and reminder priority.
- See today's scheduled doses, in chronological order.
- Explicitly confirm a dose as taken, or record uncertainty without marking it taken.
- Review history with the scheduled date/time and the actual confirmation timestamp.
- Archive a schedule without deleting its history.
- Save everything in an app-private SQLite database, with no network or camera permissions.
- Reject duplicate taken entries for the same scheduled dose, including at the database level.

The journal contains user confirmations, not medical verification of ingestion. Priority is descriptive in this milestone; it does not yet trigger alerts or alter medication instructions.

## Development

The native Android interface uses Java and Android framework views. Domain rules live in a separate Java module so scheduling and confirmation behavior can be tested without an Android device. The initial project has no third-party runtime dependencies.

### Run core and storage checks

Requires JDK 17 or newer and Python 3:

```sh
bash scripts/check.sh
```

Checks cover scheduling around enrollment, midnight, time zones and daylight saving; explicit confirmation and repeated actions; and the actual SQLite schema's persistence, uniqueness and history-preservation constraints. These checks do not exercise Android UI or Android's SQLiteOpenHelper lifecycle.

Verified on November 19, 2026: 21 core checks and 10 SQLite/resource checks passed. The Gradle core checks and debug APK build also passed using JDK 22; emulator installation and first launch succeeded.

### Build Android

Requires Android Studio with JDK 17, Android SDK platform 35, and its supported build tools. The project uses Android Gradle Plugin 8.13.0 and Gradle 8.13. Minimum supported device: Android 8 (API 26).

Open this directory in Android Studio and select the `app` run configuration. The official Gradle wrapper is included, with its distribution checksum pinned. From a terminal with the Android SDK configured:

```sh
./gradlew :core:verifyCore :app:assembleDebug
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

1. **Offline Android reminders:** notification permission, local scheduling, snooze and escalating reminders, reboot/time-change handling, and testing on the target phone. Always allow an override; phone access never depends on camera recognition.
2. **Bottle label matching:** on-device OCR of the name and strength, compared with the user-entered schedule; stable readings across frames and clear handling of uncertainty.
3. **Guided action tracking:** hand and face landmarks plus a temporal sequence detector for hand-to-mouth gestures. Gesture detection does not establish pill ingestion or swallowing. The user explicitly confirms completion.
4. **Account and Windows companion:** authenticated access, history sync, offline queues and visible sync freshness, including conflicting offline entries for the same dose.
5. **Portfolio demonstration:** optional CV overlays and measured recognition/gesture performance on consented or synthetic development data. The app will not persist users' camera frames.

See [the project decisions](docs/project-decisions.md) for the intended camera flow and privacy rules.

## Version control

Working name: `dose-companion`. Intended visibility: public. No repository or commit is created automatically. Every commit requires the project owner's explicit approval after reviewing the proposed changes.
>>>>>>> 877880f (feat: add offline Android medication journal)
