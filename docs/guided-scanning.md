# Guided prescription scanning

The cloud reader now separates camera capture, photo review and returned-text review. This is an interface change to the existing multi-photo reader; individual camera steps do not call OCR or the cloud model.

## Flow

1. **Name & strength:** point at the medicine name and printed strength, then capture. If these wrap around the bottle, another view can be added later.
2. **Taking instructions:** turn to the sentence explaining how much to take and how often, then capture. A wrapped sentence can be completed with extra views.
3. **Check your photos:** inspect the two required views, retake either in place, and add up to two optional views. Only optional views can be removed. A retake keeps the original until a replacement has been captured; returning to photo review cancels the retake. Tap to focus and dark/blank-photo rejection remain available during capture.
4. Read the retention notice and approve the upload. **Send selected photos to OpenAI** sends all selected photos in one request. A visible waiting state disables editing and resubmission. No camera step submits anything automatically.
5. **Review the details:** compare every returned field with the source photos and original label, correct missing/wrong text, then explicitly confirm. The reviewed text returns to the medication form; quantity, times and saving remain separate user actions. No dose is logged by scanning.

The four-step indicator is Label → Instructions → Photos → Details; sending happens within Photos. The camera screen contains capture guidance, not connection settings. Photo review shows connection readiness and recovery messages. Source photos can still be inspected while a request is pending.

## Session and privacy

Capture position, pending retake, selected photos, request and review edits live only in the retained in-memory session. Rotation keeps progress and resets approval checkboxes. Backgrounding/leaving clears images and unconfirmed details. Replacing/removing a photo recycles its bitmap, clears upload consent and does not trigger a request. The existing backend retention policy and duplicate-submission protections are unchanged. API keys remain on the Mac.

## Checks

`GuidedScanningTest` exercises the real screens with synthetic in-memory photos: ordered prompts, no upload from capture, retaking a specific view, four-photo limit, protected required views, optional-photo removal, rotation during photo review/retake, consent reset and background cleanup. `CloudRecoveryTest`, `CloudReviewSmokeTest` and `CloudReaderSmokeTest` cover the pending request, result review and HTTP path.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -PcloudExperiment=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.dosecompanion.app.GuidedScanningTest,com.dosecompanion.app.CloudRecoveryTest,com.dosecompanion.app.CloudReviewSmokeTest,com.dosecompanion.app.CloudReaderSmokeTest com.dosecompanion.app.localexperiment.test/androidx.test.runner.AndroidJUnitRunner
python3 server/pair_android.py
```

Device checks use **Dose Companion Lab**, never the ordinary journal, and local fixtures rather than paid provider requests. After installing, manually capture a real bottle's first two views, retake one, and add a view for any wrapped instructions. Check focus, glare and readability before choosing to send.

## Validation status

On November 25, 2026, the guided build, instrumentation APK compilation and lint passed; `git diff --check` was clean. The guided app and test APK were installed on the Samsung SM-G981W. After an interrupted USB session, the five-test suite was rerun with its output saved on the phone. All five tests passed in 137.88 seconds: guided capture/retakes/extra views and rotation, error guidance, rotation during an active request with retained edits, separate confirmations and background cleanup, and one-request HTTP transport. These checks used synthetic local fixtures, with no paid API calls. Real-bottle sharpness, glare and comfort remain manual acceptance checks for the owner.
