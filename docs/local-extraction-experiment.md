# Local label extraction experiment

This feature branch tests **LFM2.5-VL-1.6B-Extract** as an on-device drafting helper for prescription labels. It does not replace the sequential OCR scanner or establish that medication was taken. Main is unchanged.

## Current capture mode: stationary phone, rotating bottle

Open **Dose Companion Lab → Add medication → Try local extraction (experiment)**. Move close enough for the label to fill the large guide. Keep the phone still and the upright bottle in the same position. Tap **Start panorama**, slowly rotate the bottle in one direction so printed text passes the narrow green center strip, and tap **Finish panorama** after all directions have passed. Begin just before the medication name to avoid splitting it across the panorama ends.

Frames are sampled in memory at a bounded rate. ORB/RANSAC tracks moving label features in the central region while filtering stationary background matches. Only newly revealed central strips are retained. Direction changes, fast movement, weak overlap and vertical drift request recovery rather than guessed pixels. Capture is bounded to 90 seconds and a 4,096-pixel / four-megapixel strip budget. No video file is recorded. Inspect the resulting single panorama and its seams, confirm readability, then run one local-model extraction and review the returned text. Leaving the screen discards the panorama and tracking buffers.

This capture revision received a build and focused synthetic motion smoke check, with real bottle evaluation left to the owner as requested. Earlier image/model benchmarks below are historical, not validation of rotating-bottle capture. Cloud inference is not implemented.

## Previous still-photo capture workflow

Use **Dose Companion Lab → Add medication → Try local extraction (experiment)**. Existing imported model weights remain valid.

1. Capture two or three neighboring portions of **one** bottle, sweeping horizontally in one direction. Keep the camera level and at the same distance. Include roughly 25–60% shared text between neighboring photos; capture several common lines rather than just one repeated word.
2. Tap **Assemble label image**. OpenCV ORB feature matching and RANSAC estimate a small scale/rotation/translation between neighboring photos. The app rejects weak matches, duplicate photos, changing sweep directions and excessive scale/rotation changes. It never silently places unmatched photos side by side.
3. Inspect **Check the combined label**. The app draws the original pixels into one panorama, selecting one side of each overlap at its middle to avoid double text. Scroll across the image at its original pixel size and compare the original photos. Check every seam, decimal point and complete instruction line. If text is cut, duplicated or distorted, discard and retake. This is bounded image registration, not full cylindrical bottle unwarping.
4. Confirm the joined image is readable, then tap **Read this combined image locally**. There is exactly **one model call on the combined pixels**. No per-photo prescriptions are extracted or merged. Independent OCR also reads the combined image; its text is shown for comparison and is not supplied to the model.
5. Review and correct the single name, strength and directions draft. Explicit confirmation returns only reviewed text to the medication form. Set quantity/times yourself and save explicitly. Scanning never records a taken dose.

Photos, the panorama and unconfirmed drafts stay in memory. Leaving the screen or rotating clears them; in-flight native buffers are released when their worker finishes. Native model input is limited to a 1,920-pixel longest edge and 1,800,000 pixels; the model retains its normal 256-token-per-view vision preprocessing and can internally tile the **one** supplied image. Large panoramas may still lose fine print during model preprocessing. The per-reading deadline is five minutes, with late results ignored. A confidence failure requests a retake instead of producing a guessed panorama.

Build/install the isolated package without replacing the regular app or its journal:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -PlocalExperiment=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The two pinned weights below are imported once. The APK does not bundle the weights; it has no network permission. Import them before taking photos, because opening setup clears the capture session.

## Model and runtime

The [official model card](https://huggingface.co/LiquidAI/LFM2.5-VL-1.6B-Extract) describes single-image, single-turn extraction with a YAML field list and flat JSON. This experiment supplies one reviewed, stitched label image in a single turn. It uses greedy decoding, a 4,096-token context and a 256-token output cap. The combined input is bounded as described above; original capture crops are retained at up to 1,536 pixels for review. Truncated answers are rejected. CPU inference uses four threads; this is an ARM64 Android research build, not a general device compatibility claim.

[Official GGUF weights](https://huggingface.co/LiquidAI/LFM2.5-VL-1.6B-Extract-GGUF/tree/6e60b55c1312f52b94b4d8d78f83fb097a1074c1), pinned revision `6e60b55c1312f52b94b4d8d78f83fb097a1074c1`:

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| LFM2.5-VL-1.6B-Extract-Q4_K_M.gguf | 730,896,192 | `92e9a7e54a618cee4c9fbec74659c66b3f1aed3f6776f42dfba1fb211a19bd4d` |
| mmproj-LFM2.5-VL-1.6B-Extract-Q8_0.gguf | 583,109,792 | `759f5c4133ffaa534cbfb19340a1448b61606969a6d63441fea1a7bd9f4ede31` |

Imports use a temporary file, check exact byte count and SHA-256, and replace an installed file only after verification. Both hashes are checked before loading. Only public weights persist in app-private, backup-excluded storage. Weights, toolchains and generated artifacts are excluded from Git.

The JNI wrapper statically links [llama.cpp](https://github.com/ggml-org/llama.cpp/tree/f4f6f2e7db76e98c95cc9b7fc608ea669c806044), pinned commit `f4f6f2e7db76e98c95cc9b7fc608ea669c806044`. CMake checks the source archive SHA-256 `83847fb17845b04fbd0ccd604d27c1f4f755eca69fed527c2444a26e3d4f29c0`. Notices ship under `app/src/main/assets/licenses/`. The model has the **LFM Open License v1.0**, with commercial-use conditions; it is not Apache 2.0. This branch does not redistribute weights.

Build prerequisites in addition to SDK 35 / JDK 17 or 21: **NDK 27.3.13750724 and CMake 3.22.1**, installable through Android Studio's SDK Tools. An optional local toolchain under `.tools/extract/` is used in the owner's workspace; it is not part of the repository. First configuration fetches the pinned llama.cpp archive. The model/runtime need no network once installed.

## Privacy and boundaries

The application manifest removes `INTERNET` and network-state permissions. No server, telemetry, API key or cloud fallback is used. Camera images are captured through CameraX's in-memory callback; no image/video file is created. Selected images and raw OCR/drafts are not placed in saved instance state, the database or logs. Camera screens block screenshots and recent-task previews. Only explicitly approved label text can reach the medication form and database after the user saves it.

This tests transcription, not medical reasoning, prescription validation or swallowing. The alignment is not full cylindrical unwarping and cannot recover text hidden around the bottle or obscured by blur/glare. A local drug database would not establish the correct patient's prescribed directions. Human confirmation remains necessary even when views agree. On-device processing alone is not certification of compliance with Quebec privacy requirements.

## Verification and evaluation

```sh
bash scripts/check.sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -PlocalExperiment=true
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.dosecompanion.app.ExtractionContractTest,com.dosecompanion.app.ExtractionReviewTest,com.dosecompanion.app.MedicationSetupIntegrationTest com.dosecompanion.app.localexperiment.test/androidx.test.runner.AndroidJUnitRunner
# Requires both real weights in the isolated package:
adb shell am instrument -w -e class com.dosecompanion.app.ExtractionBenchmarkTest com.dosecompanion.app.localexperiment.test/androidx.test.runner.AndroidJUnitRunner
```

Tests cover invalid/truncated/extra/duplicate JSON fields, nulls, decimal conflicts, import integrity, explicit review, revoking review after edits, background/rotation cleanup and returning suggestions without automatically saving or replacing quantity. The benchmark performs real CPU inference on generated fictional labels and reports exact field results, wall time and process PSS; accuracy measurements are distinct from passing software-contract tests. Synthetic flat labels do not establish performance on curved, glossy, bilingual real bottles.

### Previous per-photo revision: historical measurements

Measured November 22, 2026 on the owner's Samsung SM-G981W (Android 13 / API 33):

- **164 core checks, 12 storage/resource checks and 14 Android instrumentation tests passed.** The Android suite includes real ML Kit OCR, review/background/rotation checks, medication-form integration, strict JSON/import contracts and the real local-model benchmark.
- A clean ARM64 debug build and Android lint passed. The isolated APK is approximately **50.6 MB**, with no bundled weights; the separately imported weights total **1.31 GB**.
- The final five-image CPU benchmark produced **5/5 structurally valid JSON answers**, with **28.2–35.1 seconds per image** and approximately **1.4 GiB process PSS**. Two or three selected views therefore take roughly one to two minutes on this phone. These are synthetic, flat, readable labels, not real-bottle acceptance results.

| Fictional label content | Name / strength result | Directions result |
| --- | --- | --- |
| LONITEN, 2.5 mg, English instructions | Both correct | Correct words, changed initial letter case |
| LONITEN, 25 mg, English instructions | Both correct; decimal shift distinguished | Correct words, changed initial letter case |
| LONITEN, 2,5 mg, French instructions | Both correct, decimal comma retained | Correct French words, changed initial letter case |
| Directions and pharmacy/count text, no drug name or strength | Incorrectly supplied pharmacy name and `30 tablets` | Correct instruction words, changed initial letter case |
| Pharmacy/count text only, no medication details | Incorrectly supplied pharmacy name and `30 tablets` | Incorrectly supplied `30 tablets` as instructions |

The aggregate scores were **3/5 names**, **3/5 strengths**, **4/5 instruction wordings** (ignoring case/whitespace), and **0/5 character-exact instruction strings**. The two missing-name/strength cases should have returned null, so these are extraction errors even though the JSON is valid. The final strength form rejects `30 tablets` as a strength; conflicting readings remain unresolved. There is no automatic semantic validation of a proposed name or instruction: the user must check these against the source photos.

An earlier unbounded 1,000 × 660 input triggered image tiling and took about **159 seconds for one image**. The 640-pixel bound avoids that cost for this experiment, with a loss of fine-print detail. Stronger exclusion wording did not eliminate the missing-field errors. An intermediate prompt also produced a bare numeric strength; the strict parser rejected it rather than supplying a unit. The final prompt explicitly requests strings with the printed units.

**Conclusion for this branch:** the local runtime and review workflow are functional, but this model is still a slow drafting helper and can invent field assignments on partial views. These measurements do not support autonomous extraction or a seamless scanning claim. A real curved Loniten bottle test remains for the owner, with manual checking of every returned field. No personal label images or patient records were used or saved during the automated benchmark.

## Combined-image revision validation

The new `LabelStitcherTest` reconstructs a synthetic label from three overlapping crops and compares actual source pixels across both seams. It also rejects blank and duplicate views. The lifecycle test checks joined-image review and clears originals/panorama/drafts on background and rotation. `CombinedExtractionTest` makes one actual local-model call on a panorama assembled from fictional partial views, with no expected prescription values in the prompt. Previous per-photo measurements above must not be treated as results for this revision.

The host suite passed 164 core checks and 12 storage/resource checks. The first joined-image run passed nine Samsung alignment/review/import/form checks and completed real inference. At a 1,280-pixel / 512-vision-token limit it took 57.7 seconds, transcribed the complete English directions, but incorrectly returned the pharmacy header as the product name and `0.5 mg` instead of `2.5 mg`. A 1,920-pixel / 1,024-global-vision-token comparison took 119.6 seconds and still misread the name and strength (`2.0 mg`), also omitting “once” from the instructions. Increasing global resolution alone did not solve accuracy. Real ML Kit OCR on the actual stitched pixels correctly read `2.5 mg` and the complete English sentence across a seam, separating the measured VLM errors from missing stitched content. The final comparison uses normal internal image tiling on the single panorama; passing workflow tests does not imply correct model extraction.
