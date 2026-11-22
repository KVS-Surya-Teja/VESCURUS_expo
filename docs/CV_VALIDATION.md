# CV milestone: implementation and evidence

22 November 2026. Started from clean `restore-v0-flow`, baseline `198b88a`.
The latest instruction deferred physical phones and scale work in favour of blind
online-image tests and an executable API. Firmware/wiring and billing were unchanged.

## Verified results

- **15 real image requests through the local HTTP API and existing Gemini key:**
  reviewed outcome **15 passed, 0 failed, 0 blocked** under the checks below.
- Eight online source photos: eggplant, tomatoes, banana, mixed onions/peppers,
  legitimate eggs, a white computer mouse, and non-food people. Additional cases
  use documented translations, rotation, occlusion, three photographic tiles,
  an empty synthetic region and a repeat observation. Two source photos (second
  eggplant and eggs) were held out from initial provider selection.
- Only re-encoded image bytes and the generic prompt/schema went to the provider.
  No source URLs, filenames, expected labels or annotations were submitted.
- Prompt and model stayed fixed throughout the evaluation. One initial evaluator
  failure omitted `White Onion` from the onion family. Visual inspection confirmed
  white onions in the source. Only the evaluation synonym changed; initial results
  were retained. No detector tuning was used to turn that result into a pass.
- Measured provider latency: **median 2,088 ms; range 1,267–4,582 ms**. API round-trip
  timings are recorded separately. Request intervals are not latency measurements.
- Final API recheck: Eggplant, Tomato and Banana, frame `501`, **2,277 ms** round
  trip / **2,201 ms** provider request. Immediate identical retry: **10 ms**, one
  provider call total.
- Incremental Android `:app:testDebugUnitTest :app:assembleDebug` passed: **14 JVM
  tests**, including the Android parser on the actual recorded provider JSON.
- **17 Python tests** passed. Failure/quota/concurrency tests explicitly mock the
  provider; those are contract checks, not recognition evidence. A Windows socket
  reset caused by replying before draining request bytes was discovered and fixed.

Evidence: [results, timings, source URLs and hashes](cv-evidence/online-photo-results.json).
Local visual report: `.local/vision/report.html`; contact sheet:
`.local/vision/results-contact.jpg`. Source images and returned observations remain
in ignored `.local/vision/`. The actual provider-response fixture and provenance
are in `app/src/test/resources/vision/`.

These checks cover label presence, absence on controls, and box-center containment
within the known photographic tile regions. They do **not** establish benchmark
mAP, exact IoU, arbitrary-food accuracy, physical camera latency, or perfect CV.
Model confidence scores are not measured accuracy percentages. Rotated/composited
photos and occlusion rectangles are identified as synthetic test transformations.

## Provider selection

Primary: explicit cloud Gemini **`gemini-3.1-flash-lite`**, using the existing key.
It succeeded on the controlled image set. No claim of universal superiority is made.

| Exploratory model request | Actual result |
|---|---|
| `gemini-3.8-flash` | HTTP 503; subsequent request timed out |
| `gemini-3.5-flash-lite` | HTTP 503, high demand / UNAVAILABLE |
| `gemini-2.5-flash` | HTTP 404, unavailable for this image request |
| `gemini-3.1-flash-lite` | Successful eggplant inference, then 15-case evaluation |

ListModels alone did not establish inference availability or quota. No invisible
provider fallback, subscription or paid upgrade was introduced. Account billing
was not changed. Public free-tier availability does not establish an account's
billing tier or unlimited usage.

The existing on-device alternative has **no `yolo_food.onnx` asset packaged here**,
no verified export provenance and no installed reference inference environment.
Its hardcoded class list is not evidence of a model. It uses default ONNX Runtime
CPU, with no accelerator configured. Hidden cloud fallback was removed; unsupported
tensor layouts now fail instead of using incorrect transposed indexing. Local
inference remains unverified. No model search/training/download was introduced.

Official references checked:
[model capabilities](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite),
[deprecations](https://ai.google.dev/gemini-api/docs/deprecations),
[pricing](https://ai.google.dev/gemini-api/docs/pricing).
Actual image requests determined the working route.

## Run and use the API

This is an optional **loopback development adapter**, not a hosted backend or a
phone synchronization server. Android calls the same cloud provider directly;
the laptop API is not required for the Android route.

From the repository in PowerShell:

```powershell
python -m pip install -r tools/vision/requirements.txt
python -u tools/vision/vision_api.py
```

This task left an instance running at `http://127.0.0.1:8765`. It makes no provider
requests when idle. `GET /health` reports configured model, request count and whether
this running instance completed inference. Configuration comes from ignored
`local.properties` (`GEMINI_API_KEY`, optional `GEMINI_MODEL`) or environment variables.
Default model: `gemini-3.1-flash-lite`. Keys are omitted from URLs and logs. APK keys
remain extractable; local configuration is not production secret protection.

`POST /v1/observations`, JSON body:

```json
{"sessionId":"experiment-1","streamId":"images-1","frameId":1,"imageBase64":"<encoded image bytes>"}
```

Example request:

```powershell
$imagePath = 'C:\absolute\path\ingredient-photo.jpg'
$payload = @{
    sessionId = 'experiment-1'
    streamId = 'images-1'
    frameId = 1
    imageBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($imagePath))
} | ConvertTo-Json
Invoke-RestMethod 'http://127.0.0.1:8765/v1/observations' -Method Post -ContentType 'application/json' -Body $payload
```

Successful observations retain session/stream/frame identity, actual analyzed JPEG,
upright dimensions, normalized YXYX boxes, engine, completion status, measured latency
and source age. EXIF is removed, orientation applied, maximum inference dimension 640.

- One provider request in flight; concurrent work returns `429 BUSY`.
- Bounded replay cache: 8 observations, sequence map: 32 streams. Identical retries
  reuse results; changed bytes with the same frame identity return 409. Obsolete
  frames return 409; expired retained observations return 410. Increment frame IDs;
  start a new stream after a capture restart.
- Bounded input sizes; malformed requests/images fail explicitly.
- Missing credentials, unavailable model, malformed/incomplete response, timeout,
  network failure and quota/rate limit differ from successful empty detection.
  A quota response starts a 60-second cooldown. No hidden retries.
- The API never creates ingredient additions, mass measurements, recipe decisions
  or food-safety/doneness claims.

Optional recording: set `$env:VESCURUS_RECORD_CV='1'` before startup. Provider text
and hashed provenance go to `.local/vision/provider-records/`. It is off by default;
the task instance has it enabled for retaining verification evidence.

Reproduce the evaluation (up to 15 real provider calls, no automatic retries):

```powershell
python -u tools/vision/evaluate.py
python -m unittest discover -s tools/vision -p 'test_*.py' -v
```

## Android preparation and remaining work

Prepared route: CameraX latest frame → upright bitmap → Gemini REST → strict parser
→ frame-bound Observation → Guide analyzed inset → bounded Ktor transport → Cook
analyzed snapshot. Shared assets: `vision_prompt.txt` and `vision_schema.json`.
Active implementation: `GuideScreen`, `GeminiIngredientDetector`, `AppModule`,
`GeminiRepositoryImpl`. Top-level `app/GeminiService.kt` is an old snippet outside
the compiled source set and is not the active route.

Removed white/oval-to-egg bias, forced cloud support flags, substring label matching,
empty-on-error semantics, stale-positive retention and boxes on unrelated live
images. Legitimate eggs remain supported. Live preview and labeled snapshots are
separate. Result expiry uses sender-reported source age plus receiver-local elapsed
time; unrelated device clocks are never directly compared.

The result gate rejects duplicates, out-of-order results, earlier sessions/streams,
and observations older than 15 seconds. Live preview expires after 3 seconds. Cook
initiates session/version synchronization before connected status. Client ownership
generations prevent old cleanup from clearing replacement connections. Snapshot
commands return explicit unavailable status. Camera disposal unbinds owned use cases.

Cook no longer auto-starts recipe selection from fluctuating camera labels or forced
Egg input. Manual confirmation supplies actual ingredient identities; failures do
not introduce unrelated egg recipes. Demonstration no longer presents fixed Egg
recognition/physical connectivity as live evidence. Full recipe/nutrition, timers,
session persistence/export and scale work is **not complete**.

Build:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-parallel
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. No clean build, cache deletion or
parallel Gradle invocation was used.

**Deferred/unverified:** CameraX on physical phones; rotation, background/resume and
two-phone reconnection behaviour; physical display latency; scale firmware/transport,
weighing associations, mounted calibration, and complete multi-ingredient cooking.
Network ownership changes are prepared and compiled, not two-phone verified.

When phone testing resumes: place tomato, remove it, replace with onion, and check
the matching snapshot/expiry on both screens. Rotate Guide; disconnect internet
while retaining local Wi-Fi; reconnect Cook repeatedly. Confirm old results never
become current or create additions. Then resume the original scale/cooking work.
