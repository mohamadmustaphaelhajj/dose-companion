# Reliability and recovery milestone

Branch: `feature/reliability-recovery`. No provider calls or personal photos are needed for the automated checks.

## Changes

- Backend failures are mapped to bounded, non-sensitive error codes: exhausted credits, rejected provider credentials, model access, rate limits, provider unavailability, timeout/connection failure, unreadable labels, malformed results and incomplete responses. Raw provider messages and label content are never echoed to the app or logs.
- The app reads these error codes and explains the action to take. A failure keeps the selected photos while the screen remains open, and requires new upload consent before an explicit retry. Connection checks remain free of image uploads.
- A memory-only Android ViewModel owns selected bitmaps, network work, the returned draft and edited review fields. Device rotation keeps this session and lets an existing request finish. Neither rotation nor a rebuilt screen automatically submits a request. Approval checkboxes reset on recreation.
- Actually leaving the screen/backgrounding the activity clears photos and draft fields and cancels the local connection. Nothing is put in saved instance state or a database. Process death cannot restore this memory-only session. Cancellation cannot retract an upload or guarantee that provider processing/billing stops.
- Client submission is guarded while a request is active. Each new explicit upload includes a random submission ID. The backend refuses a repeated ID for ten minutes, including after a failed/uncertain provider call. Receipts contain only IDs/timestamps, are bounded to 512 entries and remain in memory. This is not a cross-restart exactly-once guarantee; a new explicit retry can incur another charge.
- Unknown fields remain editable and visibly identified. Scanning and retries never write taken-dose events or save a medication automatically.

## Focused checks

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s server -p 'test_*.py' -v
bash scripts/check.sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -PcloudExperiment=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.dosecompanion.app.CloudRecoveryTest,com.dosecompanion.app.CloudReviewSmokeTest,com.dosecompanion.app.CloudReaderSmokeTest com.dosecompanion.app.localexperiment.test/androidx.test.runner.AndroidJUnitRunner
```

The backend suite exercises quota vs rate-limit classification and duplicate-ID rejection with mocked provider calls. The rotation test holds a local fixture response open, rotates the actual activity, then releases the response and verifies a single request, retained photos, retained edited strength and cleanup on true backgrounding. All fixture labels are fictional.

## Manual acceptance

1. Capture two photos, rotate the phone, and confirm the same thumbnails remain. Upload consent should be unchecked.
2. Send once, rotate while waiting, and confirm that the result arrives once. Inspect source photos; edit strength, rotate again, and check that the edit survives while final approval is unchecked.
3. Disconnect USB before sending/checking the connection. Confirm actionable connection guidance and that photos remain on the open screen.
4. Check quota/rate-limit failures using the mocked tests; do not deliberately exhaust credits or repeatedly send paid requests.
5. Leave the app, return, and verify photos and unconfirmed readings have been discarded. No new medication or taken-dose event should appear.

## Validation status

Build, instrumentation APK compilation and lint passed. The 11 mocked backend tests, 164 core checks and 12 storage/resource checks passed. On November 25, 2026, all four focused instrumentation tests passed on the Samsung SM-G981W in Dose Companion Lab: error guidance, rotation during a pending request and review edits, separate confirmations and background clearing, and one-request HTTP serialization. These used local synthetic fixtures. No paid API requests were made for this milestone.
