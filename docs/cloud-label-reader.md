# Cloud prescription reader: personal experiment

The owner opted to replace the panorama with a few separate photos, use OpenAI, and allow standard API retention for **their personal experiment**. This is a change from the earlier local-only requirement. It does not establish suitability for a wider medical-data deployment or Quebec privacy compliance.

## Current flow

Use **Dose Companion Lab → Add medication → Read label from photos (cloud)**:

1. Photograph the same bottle in two to four views: name/strength, the full taking instructions, and extra sections as needed. Photos do not need geometric overlap or a fixed rotation axis. Keep desired text within the white guide; inspect thumbnails to check sharpness and coverage.
2. Read the provider-retention notice, tick upload consent, then press **Send selected photos to OpenAI**. Nothing is uploaded just by taking pictures, checking the connection, or ticking the box. Adding/removing a photo clears consent.
3. The phone sends those selected JPEGs to the backend once. The backend includes all images in **one OpenAI Responses API request**. There is no stitching, per-photo prescription merging, prior conversation, expected prescription answer, tool use, Files API or automatic retry.
4. Review the medication name, complete printed strength with units, and full printed taking instructions against the source photos. Unknown or conflicting fields should be null, not guessed. Addresses, patient names, package counts and unrelated storage text are excluded by the extraction prompt.
5. Explicitly approve or edit the result. Editing clears approval. Only approved text returns to the medication form. Enter quantity and reminder times yourself, then save explicitly. No cloud or camera action writes a taken-dose entry.

## Local setup and phone testing

The initial backend runs on the owner's Mac, with a localhost connection carried over USB. The Mac must stay awake, the server must stay running, and the cable must remain connected during this test. This is not yet a public internet service for a friend's independent phone. A public deployment needs HTTPS hosting, proper per-user authentication and abuse/cost controls.

Create an OpenAI API project and key at [the API dashboard](https://platform.openai.com/api-keys), and configure API billing. [API billing is separate from ChatGPT](https://help.openai.com/en/articles/9039756-managing-billing-for-chatgpt-and-the-api-platform). Then, from this repository:

```sh
python3 server/configure.py
```

The key is entered with hidden input and stored in ignored `server/.env` with owner-only permissions. It is never printed, copied to the phone, or placed in Git/Gradle/BuildConfig. The model defaults to `gpt-6.2-astra`; `OPENAI_MODEL` in the same server configuration can be changed to a vision/structured-output model available to the API project. Changing configuration does not require an APK rebuild or backend restart.

Start the bridge in a Terminal that remains open:

```sh
python3 server/bridge.py
```

Build/install the cloud-enabled isolated app:

```sh
./gradlew :app:assembleDebug -PcloudExperiment=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
python3 server/pair_android.py
```

Pairing stops only the Lab app, discards its current capture session, sets up the USB reverse port, and stores a separate random backend token in the app's private settings. It does not transfer the provider key. Repeat pairing after disconnecting/reconnecting USB. The running server reads configuration from `server/.env` on each request. The app's **Check cloud connection** button refreshes setup readiness without sending any photos.

Only `-PcloudExperiment=true` enables internet permission and the cloud entry point. The ordinary offline build retains the manifest rule that removes internet permission. Both local and cloud experiments install as `com.dosecompanion.app.localexperiment`, preserving the regular `com.dosecompanion.app` journal. Never install test fixtures into the regular package.

## Privacy and cost boundary

The app and backend keep image inputs and unconfirmed drafts in memory; neither intentionally writes them to files, databases, request logs or analytics. Photo re-encoding does not copy camera EXIF metadata. The backend disables request/error-body logging, verifies TLS to OpenAI, refuses redirects, limits request/image sizes, and uses a strong pairing token. The initial HTTP hop is restricted to localhost over USB; public endpoints require HTTPS.

This **does not mean OpenAI retains nothing**. OpenAI documents standard API abuse-monitoring retention of up to 30 days, subject to exceptions. `store:false` disables saved response state but is not Zero Data Retention; special retention controls require provider approval and may have model/feature limitations. See [OpenAI's data controls](https://developers.openai.com/api/docs/guides/your-data). The app displays this distinction before upload. The owner's standard-retention approval applies to the personal experiment, not automatically to future users.

Uploads use the owner's API billing. There is one paid model request per explicit successful submission, no automatic retries, and only one active request on this development bridge. Closing the app cancels the local connection and discards local photos/results, but cannot retract images already submitted or guarantee that provider processing/charges stop. Fields returned by a model can still be wrong; human review remains mandatory.

## Validation

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s server -p 'test_*.py' -v
bash scripts/check.sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -PcloudExperiment=true
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.dosecompanion.app.CloudReaderSmokeTest,com.dosecompanion.app.CloudReviewSmokeTest com.dosecompanion.app.localexperiment.test/androidx.test.runner.AndroidJUnitRunner
```

Eight backend tests passed with provider calls mocked, including consent/authentication gates, bounded photo sets, a single multi-image provider call, incomplete/refused/malformed results and unconfigured-account rejection. Two focused Samsung tests passed: real phone HTTP serialization against a local fixture, and separate upload/result confirmations plus background cleanup. The host suite passed 164 core checks and 12 storage/resource checks. Cloud build and lint passed. A read-only provider request verified model access; no prescription photos or paid inference were used in these automated checks. Real label accuracy and latency are left for the owner's test.
