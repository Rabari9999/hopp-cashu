# Spike 6 — the web client on Kotlin/Wasm (ADR 0022, stage W1)

- **Date:** 2026-10-21 — on the maintainer's Mac (Apple Silicon), headless
  Chromium 153 and Playwright's WebKit 27.2. **No iPhone measured.**
- **Question (ADR 0022, 8 and 11.4):** can `shared` and Compose Multiplatform
  run in the browser on `wasmJs` — the dependencies, the compile, the Nostr
  crypto, the database, a first screen, its accessibility, the player — and
  does it stay inside the failure criteria: a bundle over ~10 MB, more than
  4 s to the first screen on an iPhone, a screen reader that cannot use it,
  the crypto not passing the NIP-44 vectors?
- **Answer: go, with two checks still open** — the start time on a real
  iPhone and a real screen reader. Everything in `commonMain` compiles for
  `wasmJs` after two small changes; the crypto passes every NIP-44 vector
  through `@noble/curves`; Hopp's schema runs on SQLite in OPFS. The real work
  is the database turning asynchronous (`generateAsync`), and the start time
  on a slow network.
- **Code:** branch `spike/web-wasm`. `./gradlew :shared:wasmJsBrowserTest`
  (needs `CHROME_BIN`), `./gradlew :shared:wasmJsBrowserDistribution` →
  `shared/build/dist/wasmJs/productionExecutable`.

What was **measured** and what is **estimated** is marked as such throughout.

## 1. Dependencies (measured: Maven Central `.module` files)

Every dependency of `shared/commonMain`, at the pinned version, checked for a
variant with `org.jetbrains.kotlin.platform.type = wasm` and
`org.jetbrains.kotlin.wasm.target = js`:

| Dependency | Version | wasmJs | Note / replacement |
|---|---|---|---|
| compose runtime, foundation, ui, ui-backhandler, ui-tooling-preview, components-resources | 1.11.1 | ✅ | |
| compose material3 | 1.11.0-alpha07 | ✅ | |
| compose material-icons-core | 1.7.3 | ✅ | |
| androidx lifecycle-runtime-compose, lifecycle-viewmodel-compose (JetBrains) | 2.11.0 | ✅ | |
| androidx navigation-compose (JetBrains) | 2.9.2 | ✅ | |
| sqldelight runtime, coroutines-extensions | 2.4.1 | ✅ | |
| kotlinx-coroutines-core | 1.11.0 | ✅ | no `Dispatchers.IO` on the web (§2) |
| kotlinx-serialization-json | 1.11.0 | ✅ | |
| kotlinx-io-core | 0.9.0 | ✅ | |
| kotlinx-datetime | 0.7.1 | ✅ | pulls `@js-joda/core` from npm |
| ktor-client-core, ktor-client-websockets | 3.5.1 | ✅ | |
| koin-core, koin-compose-viewmodel | 4.2.2 | ✅ | |
| xmlutil core | 1.0.2 | ✅ | |
| coil-compose, coil-network-ktor3 | 3.5.0 | ✅ | |
| reorderable | 3.1.0 | ✅ | |
| **secp256k1-kmp** | 0.24.0 | ❌ | JVM, Android, iOS, macOS, Linux only — also the newest version. Replaced by `@noble/curves` through JS interop (§3) |
| tests: kotlin-test, kotlinx-coroutines-test 1.11.0, ktor-client-mock 3.5.1 | | ✅ | |
| new for the web: ktor-client-js 3.5.1, sqldelight web-worker-driver 2.4.1 | | ✅ | |

npm packages (pinned in `kotlin-js-store/wasm/yarn.lock`): `@noble/curves`
2.4.0 (with `@noble/hashes` 2.4.0), `@sqlite.org/sqlite-wasm` 3.53.4-build1.

**Verdict:** one library missing, the expected one.

## 2. `shared` on `wasmJs` (measured)

`wasmJs { browser() }` in `shared/build.gradle.kts`, with
`binaries.executable()`. To get past dependency resolution, secp256k1-kmp moved
out of `commonMain` into a new intermediate source set **`nonWebMain`**
(Android, JVM, iOS — `applyDefaultHierarchyTemplate { group("nonWeb") }`),
behind `crypto/Secp256k1.kt`: an interface `Secp256k1Ops` and
`expect val Secp256k1`. `Keys.kt` and `Bolt11.kt` call it; nothing else used
the library. The four `expect`s that existed (`secureRandomBytes`,
`normalizeNfkc`, `canBlur`, `addAnimatedImages`) got web `actual`s up front —
`crypto.getRandomValues`, `String.normalize('NFKC')`, `true`, no-op.

The first compile of all of `commonMain` then gave **133 errors, all from one
cause**: `Dispatchers.IO` (120 uses in 12 files — repositories, value,
sync, comments; 130 unresolved references and 3 follow-on errors). It exists
on the JVM and Native only. Replaced by `platform/ioDispatcher`
(`Dispatchers.IO` in `nonWebMain`, `Dispatchers.Default` on the web — one
thread there anyway). **After that: 0 errors.** No other JVM- or Native-only
API was used in `commonMain`. In tests, `assert()` (JVM only) had to become
`assertTrue` in one file moved to `commonTest`.

| Category | Count |
|---|---|
| Missing `actual`s | 4 existing `expect`s + 2 new (`Secp256k1`, `ioDispatcher`) |
| Missing libraries | 1 (secp256k1-kmp) |
| JVM/Native-only APIs in `commonMain` | 1 (`Dispatchers.IO`, 120 uses) |

**Verdict:** compiling is not the work. Checked after the last change:
`:shared:jvmTest` (362 tests, 0 failures), `:androidApp:assembleFossDebug`
and `:shared:compileKotlinIosSimulatorArm64` pass.

## 3. Crypto (measured: pass)

Only the curve goes through JS: SHA-256, HMAC, HKDF, ChaCha20, Poly1305 and
scrypt are Hopp's own common Kotlin and run on the web as they are. The web
`actual` of `Secp256k1Ops` (`wasmJsMain/.../crypto/`) calls `@noble/curves`
2.4.0 (`schnorr.sign/verify/getPublicKey`, `secp256k1.getSharedSecret`,
`secp256k1.recoverPublicKey` with `prehash: false`, `utils.isValidSecretKey`)
through `@JsModule` declarations; bytes are copied between Kotlin's
`ByteArray` and `Uint8Array`.

The JVM-only vector tests moved to `commonTest`, reading the vector files from
constants a Gradle task generates (`generateTestVectors`), so they run on
every target. **In headless Chromium (`wasmJsBrowserTest`), all pass:**

- NIP-44 v2, the official vectors: 35 conversation keys, 32 message keys,
  24 padded lengths, 10 encrypt/decrypt, **new:** 3 long messages (65 535
  bytes) and the invalid inputs (8 conversation keys, 4 lengths, 12 payloads).
- Schnorr: event ids, signing and verification, tampering detected (`EventTest`).
- Identity vectors: Poly1305, HChaCha20, XChaCha20-Poly1305, scrypt, NIP-19,
  NIP-49 (`IdentityCryptoVectorsTest`).
- ECDSA recovery: the payee of a real Alby BOLT 11 invoice (`Bolt11PayeeTest`).

24 tests in `commonTest`, the same on the JVM against ACINQ's library.

**Found on the way:** a JS error reaches Kotlin/Wasm as `kotlin.js.JsException`,
which is a `Throwable` but **not a `kotlin.Exception`**. The invalid-key
vectors failed until the web `actual` turned noble's errors into
`IllegalArgumentException`. `commonMain` has 29 `catch (e: Exception)`; any
JS interop added later must translate its errors at the boundary the same way.

**Verdict:** pass. Speed was not an issue at this size (no timing measured).

## 4. Storage (measured: yes, once the database code is asynchronous)

SQLDelight's **web-worker driver** with a worker of our own
(`wasmJsMain/resources/sqlite.worker.js`, 60 lines) on the official
**SQLite Wasm** build, the database in **OPFS** through the `opfs-sahpool`
VFS (no COOP/COEP headers needed; without OPFS it falls back to memory and
says so). `WebDatabaseTest` in headless Chromium:

- worker + SQLite + OPFS ready: **98 ms**; `HoppDatabase.Schema` (version 12,
  19 statements, 15 tables): **45 ms**;
- a setting written and read back through the generated queries;
- a **new worker** — as after a reload — reads it back from OPFS.

**What is missing:** the driver is asynchronous, and a statement is only sent
when its `QueryResult.AsyncValue` is awaited. Hopp's code is generated
synchronous (no `generateAsync`), so on this driver `Schema.create` and every
write **silently do nothing** and every `executeAsList()` throws. The test
proves both, then awaits through a small wrapper (`AwaitingDriver`, spike only).

Measured by switching `generateAsync = true` for one compile (reverted):

| | Count | Note |
|---|---|---|
| Compile errors | 8 | writes called outside a coroutine (`SyncStore`, `Repositories`) |
| `executeAsList/One/OneOrNull()` | 68 in 12 files | still compile, but throw on the web only → `awaitAs…()` |
| `transaction { }` | 22 | become `suspend` |
| `asFlow().mapToList/One…()` | 27 | already await — work as they are |

Android and iOS keep their drivers: an async-generated database runs on a
synchronous driver unchanged. Because a leftover `executeAsList()` passes
every JVM test and fails only in the browser, `jvmTest` should run against a
JDBC driver wrapped to return `AsyncValue`s.

Also to solve: `opfs-sahpool` lets one worker hold the file — a second tab
gets no OPFS (here: the in-memory fallback). Needs a Web Lock / single-tab
rule or a SharedWorker. Integers above 2^53 would come back as `BigInt`
(msat amounts are far below).

**Verdict:** works. **Estimate:** `generateAsync` and the call sites, a test
driver that catches synchronous calls, multi-tab handling: about a week.

## 5. First screen and measurements

`wasmJsMain/.../Main.kt` renders the tour's first page — `HoppTheme` and
`OnboardingScreen` exactly as in the apps (Figtree, strings, icons) — in a
`ComposeViewport`. `?app` starts the whole app on a stub `WebPlatform`: it
draws Hopp's frame (title, tabs) and stops at the database (§4) and at CORS
(WebKit: `sovereigncast.space/directory.json` refused — ADR 0022, 6).

### Bundle (measured; `wasmJsBrowserDistribution`, MiB, without source maps)

| | raw | gzip -9 | brotli 11 |
|---|---|---|---|
| Only the tour page (dead code removed) | 11.53 | 4.16 | 3.28 |
| **The whole app reachable** (`App()` referenced) | **19.61** | **6.38** | **4.96** |
| — Skiko (Skia), fixed cost | 8.25 | 3.17 | 2.50 |
| — Hopp + libraries (Kotlin Wasm) | 9.12 | 2.39 | 1.75 |
| — SQLite Wasm | 0.83 | 0.38 | 0.33 |
| — JS glue, worker chunks, fonts, strings | 1.41 | 0.44 | 0.38 |

Against "a bundle over ~10 MB": **over in raw size, under on the wire**
(5 MiB with brotli). The ADR should say which it means. No size work was
done yet (what makes up Hopp's 9 MiB of Wasm is unexamined).

### Start time (measured, median of 5 cold loads, fresh browser context each; 390×844 at 3×)

`first frame` = the first frame Compose draws; `text` = the first frame
with the strings, which load by fetch after it. From navigation start.

| Bundle | Conditions | first frame | text |
|---|---|---|---|
| tour only | Chromium, local, unthrottled | 154 ms | 185 ms |
| whole app | Chromium, local, unthrottled | 197 ms | 236 ms |
| whole app, `?app` | Chromium, local, unthrottled | 218 ms | — |
| tour only | Chromium, **4× CPU, 50 Mbit/s, 70 ms** | 1.21 s | 1.41 s |
| whole app | Chromium, 4× CPU, 50 Mbit/s, 70 ms | 1.46 s | 1.66 s |
| tour only | Chromium, **4× CPU, 10 Mbit/s, 70 ms** | 3.30 s | 3.55 s |
| whole app | Chromium, 4× CPU, 10 Mbit/s, 70 ms | 4.37 s | 4.63 s |
| whole app, `?app` | Chromium, 4× CPU, 10 Mbit/s, 70 ms | 4.48 s | 4.76 s |
| whole app | the same, **reload from HTTP cache** | 0.42 s | 0.49 s |
| whole app | **WebKit 27.2**, local, unthrottled | 247 ms | 291 ms |
| whole app, `?app` | WebKit 27.2, local, unthrottled | 240 ms | 305 ms |

Throttling is Chromium's (CDP); Playwright cannot throttle WebKit. 4× CPU on
an M-series Mac is a stand-in for a mid-range phone, not an iPhone model.
On a slow network the time is the download: the whole app at 10 Mbit/s
misses 4 s, at 50 Mbit/s it is well inside, and a second visit from the
cache (the service worker of ADR 0022, 7 makes that the rule) takes about
half a second. **Estimated** for an iPhone on good Wi-Fi or 5G: about 1–2 s
the first time. **Not measured on an iPhone** — the open check for stage W1.

**WasmGC:** a module with a GC struct type validates in both browsers, and
the app runs in WebKit 27.2 (Playwright's own build, newer than any Safari
the ADR names). Safari 18.2, the oldest the ADR wants, was not available to test.

**Verdict:** inside the criteria on a decent network, outside at 10 Mbit/s
with a slow CPU. Measure on an iPhone before W2 goes far.

## 6. Accessibility (measured in the DOM; no real screen reader)

Compose 1.11.1's web accessibility layer is **on by default**
(`ComposeViewportConfiguration.isA11YEnabled`). It mirrors the semantics into
an invisible DOM tree in the viewport's shadow root. For the tour page, in
**both** Chromium and WebKit:

```
<div role=button> Überspringen
<div role=list>
    <div> Willkommen bei Hopp
    <div> Ein ehrlicher, offener Podcast-Player: kein Konto, kein Tracking. …
<div role=button> Weiter
<div role=button> Ich nutze Hopp schon
```

Chromium's accessibility tree (CDP) shows the same: three named buttons, a
list, the text. A **click on the mirrored "Weiter"** — what a screen reader
does to activate — turns the page in both browsers, and the tree follows.
In the whole app WebKit shows `button "Einstellungen"` (an icon button's
content description) and the three tabs by name. Turning the layer off makes
no measurable difference to the start time.

What a screen reader would see: the page's text in reading order, buttons
with their labels, the pager as a list; the logo and the page dots not at all.

Gaps:

- **Tab** stays on the canvas: keyboard focus is Compose's own and not
  mirrored to the DOM. Keyboard use and a screen reader's focus are to test.
- **No headings:** Hopp sets no `heading()` semantics anywhere (the apps
  neither) — worth adding for the web.
- In the half-working `?app` page, Chromium still had the four top-level
  buttons **without names after 5 s** while WebKit had them. Probably the
  debounced sync not settling while the broken app keeps recomposing; to
  recheck once the app works.
- Playwright's WebKit `ariaSnapshot()` reads nothing from the shadow root —
  a tool limit, the DOM is there.

**Verdict:** not a no-go — the semantics reach the browser. A pass with
VoiceOver on an iPhone and on the Mac (and NVDA or TalkBack) is still needed
before a release (ADR 0016).

## 7. Player (sketch plus a small check)

The interface is **`platform/PlaybackEngine`** (`load`, `play`, `pause`,
`seekTo`, `setSpeed`, `release`, `state: StateFlow<PlaybackState>`,
`setSkipIntervals`). `wasmJsMain/.../playback/HtmlAudioPlaybackEngine.kt`
implements it with an `<audio>` element — which plays from other hosts
without CORS — and the **Media Session API**: metadata (title, artist,
artwork), `play`/`pause`/`seekbackward`/`seekforward`/`seekto` handlers with
the listener's skip intervals, `playbackState` and `setPositionState`. The
element's events (`timeupdate`, `durationchange`, `waiting`, `ratechange` …)
feed `PlaybackState`, so chapters, accrual and the now-playing screen above
the interface stay common code. `WebPlatform.playbackEngine()` returns it.

`HtmlAudioPlaybackEngineTest` (Chromium): a generated two-second WAV loads,
reports its duration, seeks, changes speed, and the Media Session carries
the title. **Not checked:** actual playback (autoplay needs a user gesture),
the iOS lock screen, background playback in Safari and in a home-screen app.
`supportsSkipSilence` and `supportsVolumeBoost` stay `false`: both would need
Web Audio, which needs CORS on the audio host — or the proxy for audio, which
ADR 0022 rules out.

## Overall: go/no-go against ADR 0022, 11.4

| Criterion | Result | Measured? |
|---|---|---|
| Bundle over ~10 MB | raw 19.6 MiB (whole app) — over; 5.0 MiB brotli — under | measured |
| More than 4 s to the first screen on an iPhone | Mac, 4× CPU: 1.5 s at 50 Mbit/s, 4.4 s at 10 Mbit/s; ~0.4 s from cache | measured on the Mac; **iPhone missing** |
| A screen reader that cannot use it | buttons, labels and text reach the a11y tree; activation works | DOM measured; **no real screen reader** |
| Crypto not passing the NIP-44 vectors | every vector passes | measured |

**Go for W2**, under two conditions before much more is built on it: the
start time on a real iPhone (Safari and home screen, first visit and from
cache), and a VoiceOver pass over the tour and one list screen.

Biggest pieces of work found (estimates):

1. **The database asynchronous** — `generateAsync`, 68 reads, 22
   transactions, a JVM test driver that catches synchronous calls, more than
   one tab: about a week.
2. **Start time and size** — the service worker cache (planned anyway), a
   look at what fills 9 MiB of Hopp's Wasm, the iPhone measurement: 2–4 days.
3. **CORS** — every feed, image and directory goes through the proxy
   (ADR 0022, 6); the full app hit it at once (`directory.json`). A service
   of its own: the largest single item, server work outside this repository.

## Notes for the code

- The `wasmJs` target is configured in every build. Kotlin downloads Node,
  Yarn and Binaryen only when a web task runs, so `assembleFossDebug` and
  `jvmTest` do not touch them — but before this goes to `main`, check that
  the F-Droid recipe's scanner still passes, or put the target behind a Gradle
  property.
- The distribution still contains an unused raw copy of `sqlite.worker.js`
  (webpack bundles its own); `WebPlatform` keeps secrets in memory only.
- Karma needs `CHROME_BIN` (here Playwright's Chrome for Testing).
