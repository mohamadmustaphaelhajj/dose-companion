# Local label extraction experiment

This feature branch tests **LFM2.5-VL-1.6B-Extract** as an on-device drafting helper for prescription labels. It does not replace the sequential OCR scanner or establish that medication was taken. Main is unchanged.

## Try it

Build and install the isolated **Dose Companion Lab** package, which has its own empty journal and does not replace the regular app:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PlocalExperiment=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Choose **Add medication → Try local extraction (experiment)**. The two pinned model files must be imported once; the experiment screen links to the import screen when needed. The owner's Samsung development build can instead be provisioned over ADB into this isolated package's `no_backup` directory. The APK does not include 1.31 GB of weights.

1. Hold the bottle label inside the central rectangle. Tap to focus. Capture the name/strength, then rotate to the directions. Select two or three overlapping photos; inspect thumbnails before reading. Dark/blank captures are rejected, but glare and motion blur still require visual inspection.
2. Read the selected views locally. Each image gets a fresh, single-turn model context with the same field schema. No saved prescription, entered target values, previous view's answer, or OCR text is supplied to the model.
3. Inspect each source image beside its name, printed strength and verbatim directions draft. Expand its timing/OCR section to compare independent ML Kit text. OCR is a baseline for inspection, not a vote that proves correctness.
4. Fields observed in different views populate the final review form only if all nonempty readings agree. Numeric strengths compare exact normalized decimal values and units; **2.5 mg, 5 mg and 25 mg conflict**. Different directions remain separate proposals; compose the complete visible wording yourself. The app does not invent transitions, frequencies or missing words.
5. Edit every field as needed, then explicitly confirm that you checked the complete label. Any edit clears that confirmation. Only the reviewed text returns to the medication form. Enter quantity and reminder times separately, then save explicitly. Scanning writes no taken-dose records.

Switching apps, leaving the screen, or rotating the device discards photos and unconfirmed drafts. Import model files **before** capturing. Capture again after returning. A reading can be cancelled and has a three-minute limit per view; late results are ignored. Native model loading/vision encoding may finish before cancellation can release their resources, so cancellation does not guarantee immediately released RAM.

## Model and runtime

The [official model card](https://huggingface.co/LiquidAI/LFM2.5-VL-1.6B-Extract) describes single-image, single-turn extraction with a YAML field list and flat JSON. This experiment therefore uses independent per-image drafts. It uses greedy decoding, a 4,096-token context and a 256-token output cap. Each model input is limited to a 640-pixel longest edge to avoid expensive multi-tile encoding; the original review/OCR photo is retained at up to 1,536 pixels. This trades fine-print resolution for CPU latency, so fill each view with the relevant section. Truncated answers are rejected. CPU inference uses four threads; this is an ARM64 Android research build, not a general device compatibility claim.

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

This tests transcription, not medical reasoning, prescription validation or swallowing. It has no cylindrical unwarping and cannot recover text hidden around the bottle or obscured by blur/glare. A local drug database would not establish the correct patient's prescribed directions. Human confirmation remains necessary even when views agree. On-device processing alone is not certification of compliance with Quebec privacy requirements.

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

### Measured results

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
