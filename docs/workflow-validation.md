# Complete workflow validation

Date: November 25, 2026. Device: Samsung SM-G981W. Working branch: `feature/reliability-recovery`.

## Isolation and scope

The broad device suite uses **Dose Companion Tests** (`com.dosecompanion.app.remindertest`), whose tests can reset their own database. Never run these reset/migration tests against the ordinary app or Dose Companion Lab. Only the dedicated test package receives notification/exact-alarm grants. The existing Lab journal, cloud pairing and ordinary app are preserved.

`CompleteWorkflowTest` uses real activities, HTTP serialization/parsing, SQLite, AlarmManager and notification PendingIntents. Camera input is a synthetic in-memory label; a phone-local server supplies a synthetic model reply. This validates the application workflow, not real-world camera focus or cloud model accuracy. No paid API requests or prescription photos are needed. Device radios are not changed.

## Coverage

| Stage | Validation |
| --- | --- |
| Guided capture | Name/strength followed by instructions; two photos held in memory; upload requires consent. |
| Cloud failure | Fixture returns exhausted credits; photos survive, consent resets, no medication/dose is saved and no automatic retry occurs. |
| Explicit retry | New consent triggers one new request containing both images; the successful draft is editable. |
| Returned text | Editing strength clears approval; explicit approval returns the corrected strength and complete directions to the real medication form. Existing quantity survives. |
| Save schedule | Missing times block saving; adding two times and explicitly saving persists the reviewed fields, quantity, time zone and schedule, without a dose event. |
| Real alarm | No direct delivery helper is used: the OS must post the notification at the saved upcoming time. |
| Dose actions | Opening and cancelling confirmation leaves the dose pending; notification Unsure records uncertainty and removes the alert. |
| Confirmation/history | Explicit confirmation writes one taken timestamp for the correct scheduled identity and preserves the earlier uncertainty. Duplicate confirmation is rejected. History survives activity recreation. |
| Process restart/archive | A separate instrumentation invocation after killing the background test process verifies persisted history and directions, then archiving preserves both journal events. |
| Regression | Reminder actions/repeat limits, v1/v2 database upgrades, OCR/cropping, sequential field confirmation, scanner lifecycle and scan-to-form handoff. |
| Background process | A separate 30-second AlarmManager fixture must deliver before its verifier process starts; an alert alone must not write a dose event. |

## Reproduction

```sh
bash scripts/check.sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s server -p 'test_*.py' -v
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -PreminderSmoke=true -PcloudExperiment=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.dosecompanion.app.CompleteWorkflowTest#aCloudSetupReminderUncertaintyAndConfirmation com.dosecompanion.app.remindertest.test/androidx.test.runner.AndroidJUnitRunner
adb shell am kill com.dosecompanion.app.remindertest
adb shell am instrument -w -e class com.dosecompanion.app.CompleteWorkflowTest#bPersistedHistoryAndArchive com.dosecompanion.app.remindertest.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.dosecompanion.app.ReminderIntegrationTest,com.dosecompanion.app.MedicationSetupIntegrationTest,com.dosecompanion.app.GuidedLabelIntegrationTest,com.dosecompanion.app.LabelOcrIntegrationTest,com.dosecompanion.app.LabelCropIntegrationTest,com.dosecompanion.app.ScanSequenceIntegrationTest,com.dosecompanion.app.LabelCameraLifecycleTest com.dosecompanion.app.remindertest.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.dosecompanion.app.ReminderSurvivalFixture#prepare com.dosecompanion.app.remindertest.test/androidx.test.runner.AndroidJUnitRunner
adb shell am kill com.dosecompanion.app.remindertest
```

Wait at least 35 seconds, without force-stopping the package, before verification:

```sh
adb shell am instrument -w -e class com.dosecompanion.app.ReminderSurvivalFixture#verifyDeliveredBeforeVerifierStarted com.dosecompanion.app.remindertest.test/androidx.test.runner.AndroidJUnitRunner
```

For intermittent USB, launch instrumentation with `nohup` on the phone and redirect output to `/data/local/tmp/dose-complete-workflow.txt`, then retrieve that file after reconnection. Do not start overlapping instrumentation runs. The test output contains only fictional fixture names and test progress.

Return the build output to the Lab configuration after the dedicated test run, pair Lab and reopen it:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PcloudExperiment=true
python3 server/pair_android.py
```

## Results

The build, instrumentation compilation and lint passed. Host validation passed 164 core checks, 12 storage/resource tests and 11 backend tests. All **22 device checks passed**:

- Complete setup → cloud failure/retry → reviewed fields → saved schedule → real scheduled alarm → uncertainty → explicit taken timestamp → recreated history: 1 test, 73.573 seconds.
- History/directions in a new app process, then archive without erasing history: 1 test, 1.17 seconds.
- Reminder actions and limits, database upgrades, scan/form handoff, OCR/cropping and scanner lifecycle: 18 tests, 6.294 seconds.
- Background alarm preparation and verification after closing the test process: 2 checks. Verification confirmed that the notification existed before the verifier process started, and that no taken record had been written.

The initial end-to-end attempt stored the correct journal events but its recreation assertion failed because ActivityScenario lost lifecycle tracking after the notification changed the activity's intent data. The new test tracks the actual recreated activity class instead, and the entire workflow passed on rerun. This required a test-harness change, not a production app change.

A read-only OpenAI model-metadata request verified the configured key and model access. It uploaded no images and requested no inference; it does not verify billing credits or live label-reading accuracy. No paid API requests were made during validation.

Final Lab build, instrumentation compilation and lint passed. Five synthetic phone-result files were saved under ignored `.tools/workflow-validation-2026-11-25/`. The disposable test app and its instrumentation package were removed. Dose Companion Lab was paired and reopened, and the local bridge readiness check passed. No commit or push was made.

## Manual acceptance still required

Use the guided UI with a real bottle to check framing, tap-to-focus, glare and readability of curved taking instructions. A fixture cannot establish the accuracy of a live cloud reading. Check notification sound/visibility under the intended user's Samsung battery, Do Not Disturb and notification settings. This run does not reboot the user's personal phone or alter its network settings.
