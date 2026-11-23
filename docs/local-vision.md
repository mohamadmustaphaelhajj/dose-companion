# Offline label reading

This development preview adds scan-assisted setup and independent OCR/VLM comparison. It does not verify a bottle's contents, ingestion, or swallowing. No scan writes a taken-dose record.

## Current workflow: three explicit confirmations

Current validation: 150 core checks, 12 storage/resource checks, and 17 distinct Samsung instrumentation tests passed across the OCR/reminder, confirmation/form, and lifecycle runs. The dialog test caught a queued-handler timing issue; the confirmation handler is now attached immediately after dialog creation. The scanner uses ML Kit OCR, with a separate human-confirmed sequence for **name → strength → directions**. Each step displays its current reading and OCR bounding boxes. **Read and review this field** freezes the matching analysis crop and boxes; the user can correct text against the bottle before **Confirm and next**. Confirming an existing medication's name or strength requires an exact normalized match to its saved profile. Any correction is human review; the UI makes no claim that OCR or two models verified the corrected value.

Directions can be collected as multiple visible sections while rotating the bottle. Exact overlapping word sequences are joined; nonoverlapping sections remain separated by a newline. Nothing fills missing words or computes a dose. The user reviews the entire joined text, including possible duplicates or irrelevant text, before confirming it. Text is limited to 2,000 characters.

The final summary returns fields to an editable medication form, or saves reviewed directions for an existing medication only after a separate button press. Quantity and schedule remain manual. Directions are stored in `medication_directions`, added by database migration v3. The existing medication, reminder and dose-event tables are preserved. Confirmed text can survive configuration changes; unconfirmed OCR, partial sections and the retained camera bitmap do not. Neither scanning nor saving directions writes a taken-dose record.

There is no automatic progression and no VLM requirement in this workflow. The experimental model evaluation below is historical work, retained for reproducibility. It does not describe the current scanner's runtime path. Real curved-label errors can still occur and must be caught in review; the scanner does not straighten curved text.

## Historical optional local-model experiment

The VLM is optional for routine scans. The app has no internet permission. From **Set up offline label reader**, open the public download in the browser and import the downloaded file using Android's file picker. A computer can also copy the download to the phone's Downloads folder. The import checks the exact size and SHA-256, streams to a temporary private file, and replaces installed weights only after verification. No account or paid service is required. The model is separate from the APK and is never checked into Git.

- Model: [SmolVLM2-2.2B LiteRT conversion](https://huggingface.co/litert-community/SmolVLM2-2.2B), based on [HuggingFaceTB/SmolVLM2-2.2B-Instruct](https://huggingface.co/HuggingFaceTB/SmolVLM2-2.2B-Instruct).
- License: Apache 2.0; see the model repositories for notices and attribution.
- Pinned revision: `0b7c01ddf299a4500a4a87224d8e209dacf2578e`.
- File: `SmolVLM2-2.2B.litertlm`, **1,511,391,232 bytes** (1.51 GB decimal).
- SHA-256: `192e2aa412fb741acb040343d5b80b4785a57d5ffca2f085f7a49dd4b5dc72a7`.
- Runtime: [Google LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM), Android artifact `0.19.1`. Java calls the Kotlin API; no Kotlin compiler plugin is added to this project.
- Backend: GPU. CPU inference differed in the phone acceptance benchmark and is not used as a silent fallback. Incompatible devices retain the manual journal and OCR setup suggestion; a missing/unavailable VLM cannot produce a two-reader match.

After import, no connection is needed for reading, logging or reminders. A compatible 64-bit GPU and several GB of memory are needed. The first model load can be slow. The app releases the model after each verification pass; runtime compilation caches can reduce later startup time.

## Privacy and lifecycle

Camera frames, selected JPEG bytes, OCR results and generated model text remain in memory. Selected bytes are wiped when discarded, and leaving/restarting cancels in-flight work. Worker-owned bytes are wiped after the inference call unwinds. Late callbacks are rejected by a scan-generation token. Native conversation cancellation and disposal are serialized to avoid a close/cancel race. Screenshots and task snapshots of the camera screen are blocked.

Only confirmed medication fields, schedules and existing journal metadata are stored. Model weights and runtime compilation caches are also stored locally; they are not camera recordings. Android backup remains disabled. Production code does not log OCR/model responses. Benchmark logs use synthetic labels only.

## Validation and reproduction

Host checks: `bash scripts/check.sh` (150 domain checks and 12 SQLite/resource checks). Android build and lint use JDK 21. A pinned newer lint (`9.5.2`, SDK lint artifact `32.5.2`) reads LiteRT-LM's Kotlin 2.4 metadata; the lint bundled with AGP 8.13 emits incompatible-metadata errors. The newer lint also caught image encoding on the UI thread, which was moved to the worker.

Build the isolated app:

```sh
./gradlew -PreminderSmoke=true :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Install both APKs in the `.remindertest` package and import the pinned model there. Run these instrumentation classes with `am instrument -w -e class <comma-separated-class-list> com.dosecompanion.app.remindertest.test/androidx.test.runner.AndroidJUnitRunner`:

- `com.dosecompanion.app.LabelVisionIntegrationTest`: strict output parsing, actual inference, exact decimal-strength acceptance, cross-view reading, and observed accuracy/timing. Missing weights explicitly skip the model benchmark.
- `com.dosecompanion.app.LabelOcrIntegrationTest`: bundled OCR with English/French supplement text and partial views.
- `com.dosecompanion.app.LabelCropIntegrationTest`: actual OCR after cropping a textured synthetic scene with conflicting background text; blank-image contrast rejection.
- `com.dosecompanion.app.GuidedLabelIntegrationTest`: real OCR against a saved profile, preserving 25 mg as a different strength from 2.5 mg, and mapping OCR rectangles into the preview guide. The viewport binding and rounded-bottle experience also need physical camera testing.
- `com.dosecompanion.app.LabelCameraLifecycleTest`: discarding memory on background/rotation, screenshot protection, and unchanged journal history.
- `com.dosecompanion.app.LabelCancellationTest`: actual native inference cancellation, worker disposal and byte wiping.
- `com.dosecompanion.app.MedicationSetupIntegrationTest`: returning a scan suggestion to the editable form without losing dose quantity or saving it automatically.
- `com.dosecompanion.app.LocalModelIntegrationTest`: rejecting an invalid import without replacing installed weights or leaving a temporary file.
- `com.dosecompanion.app.ReminderIntegrationTest`: the existing notification, confirmation, snooze, escalation, archive and database migration regression suite. This deletes fixtures only in the isolated package; never run it against the real journal.

Optional diagnostics: pass `-e gpu false` to the model benchmark to measure the CPU path. A failed model-quality acceptance assertion is a rejected candidate, not a reason to relax exact matching.

Measured on Samsung SM-G981W, Android 13, November 22, 2026: the 363 MB SmolVLM2-500M candidate failed all four full-label extraction fixtures, including after JSON-constrained decoding, and was rejected. The larger model correctly distinguished the synthetic Loniten 2.5 mg and 25 mg labels. With refined extraction and GPU execution it matched 3 of 4 full-label fixtures; the supplement/marketing example remained unresolved. Straightforward GPU reads took about 5–9 seconds; the difficult supplement read took about 13 seconds and was rejected. GPU initialization took about 11–15 seconds in separate runs. Observed process PSS was around 3.3 GiB, excluding some GPU-driver memory. The final joint-view pipeline also passed a real-inference test combining separate Loniten name and 2.5 mg strength views. All 17 camera/model/reminder checks in that suite passed, followed by the setup-form interaction test (18 phone tests in total). These are small synthetic benchmark results, not a real-world accuracy estimate.

Real curved bottles, tiny print, glare, textured backgrounds, repeated warm runs, sustained thermal behavior, and other phones still require field testing. The VLM remains experimental, particularly for supplement labels. These limits must stay visible in release discussions.
