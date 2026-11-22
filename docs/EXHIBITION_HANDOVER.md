# VESCURUS exhibition build

22 November 2026. Kotlin/Compose; Camera phone and Cooking phone roles.
The role picker now offers Camera phone and Cooking phone only. Practice/Demo is
not exposed; its legacy records and isolated test support are retained without deleting data.
The ESP32/HX711 was unavailable during this run. Live scale transport and mounted
calibration are not integrated in this branch.
Enter externally measured amounts manually, or leave quantities unknown.

## Setup

1. Install `app/build/outputs/apk/debug/app-debug.apk` on both phones. Natural
   offline speech additionally uses the separately installed local voice provider
   on Cook; see the pinned source-build installer below. A standard phone voice
   remains available when that optional provider/model is absent.
2. Connect both phones to the same internet-capable Wi-Fi/hotspot. USB debugging is
   for installation/testing; it is not the application transport.
3. Select **Camera phone** on Guide and **Cooking phone** on the companion.
4. On Cook, enter the Guide address, port **8766**, and its six-digit pairing code.
   The connection status must show the phones are connected. Developer diagnostics
   remain in logs and exported records, not product screens.
5. Keep Camera phone aimed steadily. **All session operations happen on Cook**.
   Tap **Identify ingredient** in Cook's camera HUD. Recognition runs on Guide,
   returning the matching analyzed image. Live camera never carries old boxes.
6. Assistant reports the proposed identity and confidence. Review/correct identity
   and optional preparation (raw, sliced, diced, etc.), then record the amount.
   Without connected/calibrated hardware, enter a manual amount or leave it unknown.
   Recording preparation/weight does not confirm transfer into the dish.
7. Choose **Next ingredient** and repeat, or **Find recipes**. Optional typed or
   dictated preferences (diet, time, servings, corrections) inform AI planning.
   Dictation is an editable draft. The voice toggle remains beside the composer.
8. Review the generated recipe and its explicit optional pantry additions. It is
   labelled AI-generated, with no fabricated source or nutrition. For changes,
   chat first and choose **Update recipe with my preferences** in Recipes.
   Sourced Spoonacular and offline browsing remain available separately.
9. Start cooking, explicitly complete steps, pause/resume and use reminders.
   Timer expiry never advances the step. Chat addresses mistakes/corrections in
   current context; it does not silently rewrite completed steps or claim recovery.
10. **Finish & save** records completion and requests a new photo from Guide.
    Keep the finished dish in view. **Track** shows the local photo when delivered,
    editable dish name (pencil), completion date and recorded active cooking time.
    Available recipe nutrition is explicitly approximate, not an intake measurement;
    vitamins and minerals appear only when supplied by the recipe source. Missing values
    remain unavailable. Settings exports **Save a copy**.

## Backend and local data

The Guide phone hosts the existing Ktor WebSocket service. Cook owns recipe choice,
confirmed entries, correction/undo audit, step and pause state. Both phones now retain
local Track copies, including full corresponding conversations and finished photos. Guide receives small
session/version/context updates and supplies frame-bound observations and bounded
live frames. There is no separately hosted backend, cloud database, new subscription,
or required laptop server. `/health` on Guide identifies protocol 2.

Phone-private storage: `files/sessions/cooking.json`, `demonstration.json`, their
previous-valid backups, and retrieved `recipes.json`. Writes use a fsynced pending
file and replacement, with recovery rather than overwriting malformed data. Startup
retains session identity; recovered cooking resumes paused. Only the latest pending
full-state write is queued; every committed entry is included in that snapshot.
Android automatic backup is disabled; intentional export is available in Settings.
Export includes schema/session/event IDs, units, evidence source, confirmation and
correction history. It omits API keys and camera image data.

Chat and structured recipe planning use bounded header-authenticated REST with a
45-second request bound and one retry for temporary 502/503/504 errors. They retain
existing ingredients/recipe on failure. Earlier text/JSON calls returned 503 or
occasionally timed out. The same configured model subsequently passed a real
contextual chat request (HTTP 200, 2.82 s) and a complete production recipe-prompt
request with declared JSON schema (HTTP 200, 2.21 s). The latter kept Apple Slices'
quantity unknown and returned four steps with explicit optional pantry additions.
The production parser rejects malformed/bounded-invalid output, preserves confirmed
quantities and combined batches, accepts clarification questions, and does not
accept generated source URLs or nutrition. Four JVM cases cover this contract.
The prompt also distinguishes no added sugar from sugar-free. These are direct
API checks, not a new on-phone recipe exchange or a cooked meal. Provider
availability remains intermittent; the latest on-phone planning check is pending.

Cloud vision: explicit Gemini `gemini-3.1-flash-lite` REST requests with shared prompt
and strict schema. Spoonacular uses the existing key in an HTTP header, explicit
error states, source recipe IDs/attribution, saved local results and honest missing
nutrition. Existing APK-embedded keys remain extractable; they are not production
secret protection. No keys or Wi-Fi credentials were added to Git or logs.

No usable local model asset exists. The unused, non-16-KB-aligned ONNX 1.17 runtime
was removed. The old implementation is retained under `tools/reference/` for future
verification. Local mode explicitly reports unavailable and never silently switches
providers. Missing nutrition is unavailable. Sourced vitamins/minerals keep their own mg, µg or
IU units and appear in a collapsed, source-labelled per-serving estimate. Invalid,
conflicting and missing nutrient values are omitted; AI suggestions gain no invented
nutrition. Built-in guides are labelled as such,
not presented as Spoonacular results or measured food intake.

### Track and conversation preservation on both phones

Every actual session message is stored with stable identity, sender, verbatim text
and wall-clock timestamp in `CookingSession.conversation`. This includes recognition,
user questions, assistant replies and useful failure notices. Saved sessions and
chat-only drafts keep their conversation when reopened; starting another session
cancels pending replies so they cannot enter the wrong record. Retrying a failed
reply keeps one user prompt and one planning preference instead of duplicating them.
Historical conversations are viewable in Track, collapsed by default, with earlier
messages revealed 30 at a time. This display pagination never truncates stored chat.
Chats from older builds that never persisted them cannot be reconstructed.

Both paired roles publish their genuine local records into an additive local archive
at `files/history-replica/`. Omission from a later list does not delete a record.
Metadata and photos transfer over the existing authenticated local WebSocket in
64-KiB chunks, with SHA-256 identities, checked offsets, restart-safe staging and
fsynced atomic commits. Remote file paths are stripped and replaced with local
content-addressed paths. The sender only receives a durable acknowledgement after
any associated photo has also been saved. Reconnect resends unacknowledged records;
Track remains readable offline. A failure retains originals and offers retry.
No paid service, account, hosted database or manufactured meals are added.

All sessions and full conversations are retained; there is no arbitrary history-count
limit. Redundant timer updates are coalesced (30-second active checkpoints), with
latest plus one previous session revision retained for recovery rather than creating
a new full transcript copy on every tick. Normal APK updates must use `adb install -r`
without uninstalling or clearing app data. A second-phone copy is redundancy, not a
guarantee against deleting both applications/data. Settings' JSON export includes
conversation text; photo files are retained on each phone rather than embedded in JSON.

Guide Track is read-only. Cook edits/resumes its own current records; peer copies
remain viewable without silently taking over the other phone's session. The finish
screen links directly to Track, separates saving status from an absent dish photo,
and permits requesting the photo again after reconnection. Back returns from
Track/Settings to Cook and from Ingredients/Recipes to Assistant.

### Usability reference and constraints

The restored black startup/original logo, visible AI HUD and camera proportions are
preserved. This refinement emphasizes recovery, readable task-first actions and
honest saved/unsaved states rather than another visual overhaul. References reviewed:
[Paprika Android cooking workflow](https://paprikaapp.com/help/v3/android/) and
[Android supporting-pane guidance](https://developer.android.com/develop/adaptive-apps/guides/build-a-supporting-pane-layout).
Mobbin's detailed catalogue was not exposed through available web access and no
Mobbin connector was available; no claim is made to have copied or reviewed its
restricted screens. Figma AI reference-image limitations remain as documented below.

## Current exhibition refinement

The current changes start from remote `759002f`, preserving its startup entrance,
Kotlin/Compose architecture, recent conversation context and session/CV integrity.
The startup now uses the user's original `VESCURUS_with_text_to_the_right.svg`
(source retained in `app/src/main/artwork/`). Its paths and Cambria wordmark are
outlined into `vescurus_startup_brand.xml`; no replacement logo or rendered glow.
The launch window, system bars and entrance background are pure black. The
original 0.8→1 zoom/fade (1000 ms), 2200 ms entrance duration and 600 ms transition remain.
The full lockup fits the safe viewport in either orientation; no duplicate title
or tagline is added.
The camera and chat remain central. The restored full-width camera uses about
44% of the idle portrait viewport (36% while cooking), with rounded lower corners
and overlaid controls. The visible **AI HUD** switch shows/hides detection boxes
on the matching Last scan image; it does not put stale boxes over live video.
In landscape (at least 600 dp wide), camera and assistant use side-by-side panes.
The camera collapses for the keyboard; draft text and HUD state survive viewport
changes. Portrait and landscape keep Live/Last scan separate.
Tabs and navigation have less visual weight;
the pre-cooking recipe is a compact card. Ingredient correction/removal live
inside the overflow menu; recording an ingredient and confirming physical transfer
remain separate actions. Unknown amounts remain unknown. The composer exposes spoken guidance and
native dictation; dictation produces an editable draft, never an automatic send.
Dictation results from a different session are ignored.

Camera phone is a passive aiming surface. Cook starts **Identify ingredient**;
automatic scanning and camera-side session controls are not exposed. A separate result view shows the exact analyzed image.
Repeated slices retain their boxes but share one non-overlapping identity label;
labels draw after outlines. Confidence appears with the actual recognition proposal in Cook, as requested.
Internal frame/session IDs, model names, revisions and audit disclosures stay out
of product screens.
Full machine-readable evidence remains in exported records and developer logs.
Camera phone navigation contains Camera, Track and Settings. Track is read-only
on Guide and includes its offline local copies; cooking operations remain on Cook. Demo/Practice is removed from the role picker, including restoration
of a previously saved Demo role. Existing stored records are preserved.

### Whole-conversation acceptance review

| Requested behavior | Current evidence / remaining boundary |
| --- | --- |
| Original startup, black background, zoom, saved roles | Original supplied vector/outlined wordmark and original timing; fresh launch and viewport tests. |
| Prominent camera, visible AI HUD, persistent typed/dictated assistant | Native layout tests; latest physical rotation/keyboard/camera verification still pending. |
| Cook controls; Guide holds camera; recognize one item, review confidence/condition | Implemented with correlated frame/session/request identity; earlier real multi-food and empty scenes, current-prompt photo replay. |
| Explicit quantities, correction, Next / Find recipes | Manual/unknown amounts supported; no invented scale readings or inferred transfer. |
| Real calibrated weighing, five-second settling, LCD grams | Gate prepared and tested; ESP32/sketch/stand unavailable, so transport, final calibration and measured performance remain pending. |
| Dietary/time preferences, pantry nuance, mistakes and corrections | Saved planning notes + full conversation + contextual AI requests; source-backed structured recipe checks passed. Recovery is conversational advice, not automatic rewriting of remaining recipe steps. |
| Natural speech, speaker 5, speed 1.1 | Kitten local provider benchmark passed on Samsung; separate provider/model installation remains required. |
| Pause/resume, user-controlled timer/step advancement | Automated flow and earlier physical checks; timer expiry never advances steps. |
| Finish photo, editable title, time/date, nutrition, journal | Implemented, sourced nutrient units retained; no new real cooked meal or mounted-scale nutrition/intake claim. |
| Histories, full chats and photos on both phones | Bidirectional private archive + real socket separate-storage tests; final two-physical-phone upgrade verification remains pending. Older unsaved chats cannot be recreated. |
| No Demo, consumer-facing screens, original brand and useful animation | Demo unreachable in product; legacy records/tests isolated and preserved. No fake cooking history added. |
| Local/simple backend, ordinary commits and preserved application data | Existing phone-hosted Ktor service reused, private atomic files, install-over-existing-data workflow; no separately hosted database. |

### Natural offline speech

Requested voice: **speaker 5 / Rosie, speed 1.1**, using Apache-2.0 Kitten Nano
0.8 FP32. VESCURUS selects the named voice explicitly; other apps' default speech
engine is unchanged. Cooking text is normalized before speech. Muting, changing
sessions and shutdown cancel playback. Phone speech remains a guarded fallback
if the optional provider cannot initialize; provider failure must not crash cooking.

Two upstream adapter issues were discovered during actual WAV generation checks:
the official Sherpa Kitten engine returned only a WAV header because its service
ignored returned audio when this model did not invoke the streaming callback;
Hayai 2.5.1 declares a signature-only service permission that prevents ordinary
Android TTS clients from binding. Neither silent output nor successful engine
initialization alone counts as a voice pass. Kokoro produced real audio but took
about 33 seconds for a short phrase on Samsung, too slow for this demonstration.

Hayai is GPL-3.0-or-later; the model and Sherpa runtime are Apache-2.0. The
provider remains a separate Android application using the standard TTS interface.
The local Hayai adapter uses pinned upstream source with its own package
`dev.vescurus.voice` and a corrected service declaration. It does not modify OS
permissions. Reproduction and model provenance are in `tools/voice/engine.json`
and `tools/voice/install_engine.py`. Source/model downloads and APKs stay in ignored
`.local/voice/`; large binaries are not committed. The Samsung real-audio test passed with exact voice
`kitten-nano-en-v0_8-fp32#5#expr-voice-4-f` at 1.1: 235,686-byte mono 24-kHz WAV,
4.909 seconds of audio, generated in 5,807 ms. The application log subsequently
confirmed “Offline voice ready: speaker 5, speed 1.1”. This is a generation test;
perceived naturalness remains the user's listening judgment.

Sources: [Hayai source](https://github.com/HayaiApp/HayaiTTS/tree/v2.5.1),
[Kitten model/license](https://huggingface.co/KittenML/kitten-tts-nano-0.8-fp32),
[Sherpa Kitten implementation](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.2/sherpa-onnx/csrc/offline-tts-kitten-impl.h).

### Figma AI and production UI refinement

[Editable Figma file](https://www.figma.com/design/Zcx3aNpssrtaWoefR8OLJJ)
contains the original supplied vector logo, actual Cook/keyboard/camera references,
explicitly labelled journal test fixture, and the complete product/constraints brief.
The Production UI page has editable native screens/components, Roboto typography,
Material library instances, source-derived color tokens, and only discrete camera
content as image fills. It is a design reference, not physical verification.
[Native design render](ui-review/figma-native-direction.png).

Figma AI generated an initial text-based four-screen concept using plan credits.
The subsequent six-reference-image Weave run was prepared with the actual logo,
physical screenshots and full brief, but rejected because GPT Image 2.5 is not
available on the linked Free plan; **that run did not execute or charge**. No saved
Weave workflows were available. No purchase or hidden alternative generation was
made. Actual assets were not claimed to have reached that blocked model run.

Compose follows the useful AI direction: quieter rounded camera HUD, restrained
gold and a clear microphone, one primary next action, recognition review ahead of
an already-selected recipe, passive Guide, and photo-led compact journal entries.
Ingredient bookkeeping is expandable; absent nutrition does not create an empty
chart. The original startup entrance and native camera/session logic remain.

Planning notes are stored privately with the session, separately from the rolling
scan conversation, so early dietary/time constraints survive repeated recognition
and app restart. Request generations and cancellation prevent an old recipe job
from blocking a new session or speaking into a different session. User ingredient/
preference changes and starting cooking also invalidate an unfinished plan.

### Cook-owned workflow and finished records

Remote recognition commands are restricted to the current paired owner/session,
with bounded request-ID deduplication. The resulting observation carries the request
identity, preventing an older in-flight result from satisfying the next scan.
Normal frame/stream/session freshness gates remain. No recognition adds entries
without review. Preparation proposals are a bounded optional visual vocabulary,
not proof of doneness/safety; user correction is retained with the ingredient.

Finished photos use a one-shot request after completion; Guide supplies its next
new frame, Cook checks the session and pending request, then stores it privately.
Reconnect/session changes invalidate unfinished requests. A missing photo remains
missing. Titles do not rewrite source recipe identity. Active duration is accumulated
with the existing monotonic cooking timer and excludes pauses. Old records keep
unknown duration instead of invented timing. New fields have serialization defaults.

The pure ScaleMeasurementGate has a five-second continuous stable-reading window,
explicit calibrated-zero baseline, configurable measured tolerances, identity/epoch/
sequence checks, packet-gap/freshness rejection, and explicit pending association.
Its automated tests do not establish hardware accuracy. No ESP32 endpoint or LCD
firmware is claimed integrated without the actual working sketch/hardware.

### Local service shutdown

A physical role-switch test found a Netty shutdown crash with a WebSocket still
connected (`RejectedExecutionException: event executor terminated`). The Guide now
uses the already-present Ktor CIO server engine. The protocol, port, pairing,
ownership and freshness rules are unchanged. The Android protocol test additionally
stops/restarts Guide with Cook still connected, checks that old observations clear,
and accepts a fresh observation after recovery. Physical confirmation is recorded
in the final verification below; the engine change is not itself proof of success.

### Physical verification in this continuation

- Both original authorized phones received the same debug APK with data preserved.
- The returning-role startup entrance was observed in the real Samsung hierarchy;
  camera video, keyboard opening/closing, workspace switching and scrolling were
  exercised. Samsung's configured display density and 1.1 font scale were not changed.
- A blind real-camera plate scene returned Apple and Orange with multiple boxes;
  session `8402bd2f`, stream `b094e8cf`, frame 339, 3,653 ms. Expected identities
  were never placed in the inference request. A separate plain-view scene returned
  no food. Original input/result captures remain under ignored `.local/device/ui-synthesis/`.
- A manual Apple entry was saved with unknown amount, then corrected to Apple Slices.
  Its ID was retained, revision advanced to 3 and no transfer was confirmed. It
  survived installs/restarts. A Spoonacular query displayed an honest no-matches
  result; no matching fruit recipe or physically cooked meal is claimed.
- A genuine cloud chat exchange remembered the earlier mild-food preference and
  named the selected Tomato & onion sauté. This was a recipe/context check,
  not evidence that fruit was cooked using that unrelated recipe.
- The user confirmed native dictation and the earlier phone-voice reply worked.
  Replacement offline voice verification is tracked separately above.
- Same-subnet Wi-Fi temporarily failed direct reachability with “No route to host”.
  Rejoining Redmi to Wi-Fi restored reachability and automatic pairing without
  resetting session data. Same subnet alone does not establish a healthy local link.
- On Redmi, changing from the paired Camera role to role selection and back
  preserved process 31348 and reconnected automatically; the former connected
  shutdown crash did not recur in this check.
- The simplified build was installed on both phones with data preserved; Redmi
  was restored to Camera phone and automatically paired with Samsung. The latest
  dark/covered input correctly delivered an empty food result to Cook.
- ADB can still omit static screen regions while video updates. Earlier physical
  display confirmation was normal; screenshots are assessed with that limitation.

### Final verification gate

Latest local debug APK available for later installation: `2aa09cf14f4ef338245979c62fb3b2a2556b3daecffcd991971336ab7bda6042`.

Last physically installed debug APK SHA-256 (both devices, before disconnection):
`bef216dd77a0078a57f71de5e6adc7ad7d2121d948afb17f82b3e96fd7d4e0b2`.
Redmi `aibyojwcfyemdmce` and Samsung `RZ8M906DQ3K` were independently checked
against the local APK; data was preserved. Natural voice provider is additionally
installed on Samsung. Physical camera/control checks used these two devices.

The final build completed application/test APKs, **64 JVM tests with no failures**,
and lint with **0 errors** (warnings/hints remain). **21 Python API tests**
also passed, covering preparation and request-correlation parity. The earlier lint internal FIR
analysis failure did not recur on the successful gate. Seven Android tests passed on Samsung (61.64 seconds), including non-skipped
real Kitten audio generation, unknown quantity/correction/removal, retained recipe
with camera review, protocol recovery/remote commands/photos, and storage recovery.
The preparation-only revision/audit correction, later UI refinement and recipe-job
ownership fixes were added afterward. The user intentionally disconnected both
phones; the newest APK needs later physical installation/recheck. Two additional
Android regression cases cover late recipe replies and starting cooking while a
planner is pending. The [successful GitHub gate](https://github.com/KVS-Surya-Teja/VESCURUS_expo/actions/runs/45060003847)
verified application revision `2b5507bd7f3b626a4aba33057cbaa9cb6211b6b2`: **64 JVM
tests; 15 Android tests passed, 1 optional installed-voice test skipped** on API 35;
app/test APK builds and lint completed with 0 errors. The real voice generation
pass belongs to Samsung, not that emulator skip. The downloaded CI artifact was
verified against SHA-256 `b70469d733711a58a9215bf4c79f5145041b3f133fc6273db9a0edb5ed3e9026`.

New history checks passed on real loopback TCP using **separate private storage
and preferences per simulated peer**, not a shared-file shortcut. They verify
bidirectional union, complete timestamped chats, exact JPEG bytes, durable ACKs,
reconnection, offline restart and no duplicate records. Other Android cases verify
failed-reply retry without duplicate prompts/preferences, chat-only draft reload
and resumption, stale-reply rejection, read-only Guide history, full conversation
expansion and tiny/large nutrient values with correct units. Seven JVM archive
cases include 121 records, metadata above 3 MB, interrupted/corrupted transfer,
local-path protection and bounded redundant revision growth. Four nutrient JVM
cases cover source units/conflicts, invalid/missing values and backwards-compatible
serialization. The older exhibition fixture's View-in-Track callback omission was
fixed; the complete final gate passes. These are **not** two physical phones or a
real cooked meal. Both phones need the new APK and pairing before their genuine
history starts copying; the last installed build remains unchanged above.

Three Android tests exercise 360×720 and 800×360 dp configurations:
startup/role selection/Guide, camera and AI HUD plus retained chat draft and ingredient
actions, and Track/Settings navigation. These are controlled Compose viewport tests,
not physical rotation or camera sensor checks on the disconnected phones. The
viewport-only fixture captures include the host emulator's unused white area and
system bars; those areas are outside the tested app viewport.
[Startup portrait fixture](ui-review/startup-portrait-ci.png),
[startup landscape fixture](ui-review/startup-landscape-ci.png),
[Cook landscape fixture](ui-review/cook-landscape-ci.png),
[Guide landscape fixture](ui-review/guide-landscape-ci.png).

Latest native CI screens, using real Compose components and isolated fixtures:
[Cook](ui-review/cook-current-ci.png),
[cooking step](ui-review/cooking-step-current-ci.png),
[journal fixture](ui-review/journal-current-ci.png),
[recipe-ready fixture](ui-review/recipe-ready-current-ci.png).
These are emulator checks, not new physical-phone verification. The Cook layout
fixture renders CookScreen directly without shell navigation; journal/cooking
fixtures deliberately use isolated legacy test storage and do not expose a Demo
role in the application. The layout/render review found the expected camera,
composer, title editing and collapsed bookkeeping without overlapping controls.

Earlier physical review images: [Cook controls](ui-review/physical-cook-controls.png),
[keyboard](ui-review/physical-cook-keyboard.png),
[journal fixture](ui-review/physical-journal-fixture.png). The journal image is an
isolated automated fixture on Samsung, **not real cooking or measured intake**;
its Practice label belongs to retained test support, not an exposed Demo role.
Actual main-role screenshots contain no Demo controls.

Isolated test fixtures are never
represented as a physically cooked meal or a real scale measurement.

### Scale: reported wiring and pending work

Reported assembly: HX711 DT **18**, SCK **19**; I²C SDA **21**, SCL **22**;
16×2 LCD at **0x27**; `HX711_ADC` and `LiquidCrystal_I2C`; provisional factor **350**.
No working sketch was present in the repository or supplied in this run, and no
ESP32 USB device was connected. These are user-reported pins, not inspected wiring.
No firmware, real scale transport, tare/calibration response or measured quantity
has been verified or substituted with simulated data.

After mounting, extend the actual sketch: responsive HX711 sampling, separately
throttled LCD/network updates, no blanket ±5 g suppression, explicit tare and
persisted calibration. A future local transport can reuse Guide's HTTP service
and forward scale packets to Cook, the session owner. Packets need device/boot ID,
sequence, sender uptime, tare/calibration epochs, stable/unstable state and grams;
restarts or epoch changes invalidate any unfinished before/after baseline.
Duplicates must not create repeated ingredient entries. Association and transfer
remain explicit user confirmations.

Mounted calibration procedure (pending): record zero with the final platform/bowl;
apply several known loads spanning the useful range; repeat each placement at
least five times; record settling, error against each known load, spread, zero
return and drift. Repeat after bowl removal/replacement and device restart.
Report the actual error/repeatability before claiming useful 1 g performance.
Displayed 1 g increments do not establish 1 g accuracy. No measured end-to-end
cooking demonstration can be claimed until that hardware is present and calibrated.

## Earlier UI synthesis (historical baseline)

The UI combines the camera/chat presentation of `198b88a` with the CV and session
implementation of `87752d3`. Live video has no detection boxes. Analysis displays
the exact image and dimensions supplied by its observation. Camera capture,
recognition, transport, freshness and session reducers were retained.

- The existing startup logo fade/scale animation and screen transition are retained.
  A fresh launch shows the entrance before opening the saved phone role. Restored
  screen state after the entrance avoids replaying it on ordinary activity recreation.
- Cook has Assistant, Ingredients and Recipes workspaces, with a persistent chat
  composer in Assistant. The current instruction appears above conversation.
- The camera minimizes for the keyboard and small viewports; it can also be collapsed.
- Session IDs, revisions, engine and frame details are in Settings disclosures.
  Recognition failures, connection problems and save errors remain visible.
- Assistant requests include recent conversation and the current confirmed session.
  Replies and speech are cleared when switching sessions. Chat requests do not
  include a camera image and must not claim to observe food.
- Guide's history is read-only; draft resumption is available in Cooking/Practice.
- `CookPreviews.kt` uses current components, including a narrow, larger-font recipe
  card. These are component previews, not a simulated full-screen workflow.

## Historical emulator verification of UI synthesis

The application revision was verified by [GitHub Actions run 45015967411](https://github.com/KVS-Surya-Teja/VESCURUS_expo/actions/runs/45015967411)
at commit `3f056a58ceef3b16bb81ae5af296a101df7ed885`:

- Debug application and Android test APKs compiled successfully.
- **33 JVM tests passed**. New assistant-context cases cover conversation history,
  recipe/step context, distinct units, unknown quantities and bounded history.
- **5 Android tests passed** on an Android 15/API 35 Pixel 5 emulator. These cover
  camera/chat reachability, explicit non-egg recipe completion, pause/resume,
  timer behavior, storage recovery and the existing loopback protocol checks.
- Lint completed with **0 errors**, 86 warnings and 2 hints. Existing dependency,
  resource and style warnings remain; this is not a warning-free build.

These checks use isolated test/manual data and do not call live cloud vision or
Spoonacular. The physical-phone evidence below belongs to the preceding CV build;
it did not establish physical installation at that stage. Current device results
are recorded in the refinement section above.

### Native UI review

These unedited images come from the successful Android emulator run above.
Cook home is an isolated CookScreen with safe system insets and no paired camera;
the active step is the full app shell in explicitly labelled Practice mode, using
manual Tomato/Onion test data. They demonstrate layout, not live recognition or
weighing. The former image intentionally omits the outer shell navigation.

| Camera and Assistant workspace | Active cooking and chat |
| --- | --- |
| ![Unpaired Cook camera and Assistant](ui-review/cook-home.png) | ![Practice recipe step and chat](ui-review/cooking-step.png) |

The CI runner clears only a specifically identified background Pixel Launcher ANR
if it obstructs review; application errors and test failures remain visible. Full
reports and additional captures are retained in the linked run's artifact.

## Previous physical build verification (`87752d3`)

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
