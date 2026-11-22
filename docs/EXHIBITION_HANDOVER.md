# VESCURUS exhibition build

22 November 2026. Kotlin/Compose; existing Guide, Cook and Demonstration roles retained.
This build deliberately bypasses the unavailable physical scale. No scale reading,
calibration result or ingredient mass is fabricated. Explicit manual amounts and
unknown quantities are supported.

## Setup

1. Install `app/build/outputs/apk/debug/app-debug.apk` on both phones.
2. Connect both phones to the same internet-capable Wi-Fi/hotspot. USB debugging is
   for installation/testing; it is not the application transport.
3. Set the camera phone to Guide and the companion phone to Cook.
4. On Cook, enter the Guide address, port **8766**, and its six-digit pairing code.
   A synchronized session/version must appear on both phones before relying on it.
5. Guide: tap **Analyze scene**, or deliberately enable Auto. Cook: open the camera
   panel and choose either Live preview or the matching Analyzed snapshot.
6. Review a proposed identity or add one manually. Enter a measured amount yourself
   or leave it unknown. Confirm transfer separately after actually transferring it.
7. Search Spoonacular explicitly, choose a saved result, or browse labelled built-in
   offline guides. Check recipe targets and additional ingredients separately.
8. Start the recipe, explicitly complete steps, pause/resume as needed, and explicitly
   confirm final completion. Timer expiry only prompts a check.
9. History retains completed sessions and saved drafts. System exports JSON through
   Android's document picker. Demonstration records are separate from live records.

## Backend and local data

The Guide phone hosts the existing Ktor WebSocket service. Cook owns recipe choice,
confirmed entries, correction/undo audit, step and pause state. Guide receives small
session/version/context updates and supplies frame-bound observations and bounded
live frames. There is no separately hosted backend, cloud database, new subscription,
or required laptop server. `/health` on Guide identifies protocol 2.

Phone-private storage: `files/sessions/cooking.json`, `demonstration.json`, their
previous-valid backups, and retrieved `recipes.json`. Writes use a fsynced pending
file and replacement, with recovery rather than overwriting malformed data. Startup
retains session identity; recovered cooking resumes paused. Only the latest pending
full-state write is queued; every committed entry is included in that snapshot.
Android automatic backup is disabled; intentional export is available in System.
Export includes schema/session/event IDs, units, evidence source, confirmation and
correction history. It omits API keys and camera image data.

Cloud vision: explicit Gemini `gemini-3.1-flash-lite` REST requests with shared prompt
and strict schema. Spoonacular uses the existing key in an HTTP header, explicit
error states, source recipe IDs/attribution, saved local results and honest missing
nutrition. Existing APK-embedded keys remain extractable; they are not production
secret protection. No keys or Wi-Fi credentials were added to Git or logs.

No usable local model asset exists. The unused, non-16-KB-aligned ONNX 1.17 runtime
was removed. The old implementation is retained under `tools/reference/` for future
verification. Local mode explicitly reports unavailable and never silently switches
providers. Missing nutrition is unavailable. Built-in guides are labelled as such,
not presented as Spoonacular results or measured food intake.

## Verification record

- Latest JVM run: **29 tests passed**; actual results are retained under
  `app/build/test-results/testDebugUnitTest`. Optional CV/API adapter: **17 tests passed**.
- Final Android run: **4 tests passed** on the Redmi. The non-egg tomato/onion
  workflow verifies manual entries, timer not advancing the step, pause/resume,
  completion, reload and history. Separate cases cover Android corruption recovery
  and real loopback pairing/session/frame/reconnect/heartbeat behavior. Protocol
  fixtures are explicitly test data, not recognition evidence. MIUI requires the
  test activity to be foregrounded after instrumentation starts; the helper handles
  this and checks that the device is unlocked before starting.
- Real phone camera: user-confirmed Pomegranate, corroborated by logs; then a saved
  real Rusk image recognized as Rusk (score 0.98, 3,919 ms inference) and delivered to
  Samsung with identical session/stream/frame identity, frame 729. A subsequent
  no-food frame 1068 was received and rendered (2,690 ms inference).
- Guide: Redmi 22101316I, Android 13/API 33. Cook: Samsung SM-M307F, Android 11/API 30.
  Both were verified on the 192.168.0.x Wi-Fi network after correcting the initial
  different-subnet setup and one mistyped address.
- Cold-start check: the real app restored its same demonstration session and manual
  Tomato 200 g entry after force-stop/relaunch.
- Actual Android document-picker export saved to Downloads and was pulled/parsed;
  session identity, MANUAL evidence and GRAMS were verified.
- Actual Spoonacular request from the phone returned source-attributed recipes and
  nutrition. Reviewing the result found an unsupported water-cooking method; its
  phrase was added to the pan filter and a focused regression test.
- Lint completed with zero errors. Dependency/update, resource and style warnings
  remain; broad dependency upgrades were intentionally avoided. The ONNX native
  alignment warning was addressed by removing the unused native dependency.
- Portrait and landscape layouts were inspected; temporary rotation settings were
  restored. The final full Samsung screen was visually inspected with matching
  retained image, fitted bounding box and frame caption, without the earlier
  partial-black screenshot artifact in that snapshot capture. Live-preview ADB
  captures can still intermittently omit static areas while video updates, even
  with the phone awake and unlocked. The user confirmed that the title, card and
  bottom tabs look normal on the physical Samsung display. This remains an ADB
  capture limitation; the intermittent screenshot artifact itself is not resolved.
- Final real camera check: Roasted Chickpeas, score 0.98, frame 294, 4,296 ms;
  Samsung displayed the matching image and box. After the user removed the food,
  a new stream's frame 85 returned no detections in 2,786 ms and Samsung displayed
  “No identifiable food.” Expected identities were never supplied to the model.
  Metadata is retained in `docs/cv-evidence/two-phone-validation.json`; actual
  source photos and Samsung screenshots remain in ignored `.local/device/`.

- Final physical restart checks passed on both phones: stopping Guide changed Cook
  to reconnecting and cleared current camera evidence; relaunching Guide restored
  pairing automatically. Two Cook force-stop/relaunch cycles retained session
  `8402bd2f`, revision 0, with zero entries (recognition never silently adds food).
  Non-empty session persistence is covered separately by the actual demonstration
  reload/export and instrumentation checks above. Both installed APK hashes match
  the final build: `240871d80a326fd8dfb6d0fb2005cfc800be1349117a244bf4f480225a78901a`.

This is a controlled exhibition validation, not universal CV accuracy, food-safety,
scale precision or production-readiness certification. Model scores are not measured
accuracy. Internet services remain subject to their available quota. Weighing,
mounted calibration and a physically cooked meal remain outside the tested scope.

## Rebuild and retest

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-parallel
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python -X utf8 tools/run_phone_tests.py --device DEVICE_SERIAL
```

The UI tests use isolated cache directories with explicitly manual demonstration
data; they do not erase a user's sessions. Source-frame evidence and diagnostic
screenshots are in ignored `.local/device/`. Debug builds retain only the latest
12 analyzed observation/image records in `cache/vision-evidence/`; release builds
skip that recorder. The earlier blind online-photo evidence remains documented in
`docs/CV_VALIDATION.md` and `.local/vision/report.html`.
