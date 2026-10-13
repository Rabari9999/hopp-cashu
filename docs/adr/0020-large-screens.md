# ADR 0020 — Large screens: tablets, iPad, Mac

- **Status:** accepted
- **Date:** 2026-10-13

## Context

Hopp was laid out for a phone held upright. On an emulated Pixel Tablet in
landscape it was not usable (device feedback, confirmed 2026-10-13 on
Robolectric screenshots at 1280 × 800 dp):

- **Now playing** sized its cover to the full width and kept it square
  (`fillMaxWidth().aspectRatio(1f)`) in a column that does not scroll: about
  1240 dp of cover in an 800 dp window, every control below the edge. A phone
  on its side had the same fault.
- Everything else stretched across the window: episode rows of 1200 dp with
  their play button far from the title, show notes at ~200 characters a line,
  half of Home empty.
- The bottom bar and top bar took ~140 of 800 dp in landscape.
- Library → show → episode were three full-screen steps where a large screen
  could show list and detail side by side.

The platforms do not let us avoid it:

- **Android:** Hopp targets SDK 37. From Android 16 on, a large screen
  (smallest width ≥ 600 dp) ignores orientation and resizability restrictions,
  so locking to portrait is not an option; split screen, desktop windowing,
  Chromebooks and foldables give any size.
- **iOS:** the app is already built for iPhone *and iPad*
  (`TARGETED_DEVICE_FAMILY = "1,2"`, all four iPad orientations, no
  `UIRequiresFullScreen`), so it runs in Split View and Stage Manager at sizes
  that change while it runs. App Review expects an app that claims iPad to work
  well on it (guideline 2.4.1), with iPad screenshots.
- **Mac:** the options were

  | Way | Effort | Verdict |
  |---|---|---|
  | The iPad app on Apple-Silicon Macs ("Designed for iPad") | a switch in App Store Connect | **chosen** — a resizable Mac window; playback, media keys and Now Playing through the iOS APIs |
  | Compose Desktop (JVM) | high: playback engine, downloads, keychain for the NWC secret, packaging, signing, notarisation, updates | only on real demand |
  | Mac Catalyst | — | Kotlin/Native has no Catalyst target |
  | Compose on native macOS | — | experimental |

  So the Mac comes almost for free once the layout follows the window.

## Decision

### The layout follows the window, never the device

A tablet in split screen is a phone, a phone on its side is wide, a Mac window
is whatever the listener drags it to. Every layout decision reads the window's
size (`BoxWithConstraints` at the app's root and in now playing), not the
device type or the orientation.

Breakpoints, those of Material 3's window size classes:

- **compact** below 600 dp wide — the phone layout as it is;
- **medium** 600–840 dp, **expanded** from 840 dp — the large-screen layout;
- **wide** (now playing): wider than tall and at least 480 dp — side by side.

### Step 1 — usable on any screen (done 2026-10-13)

- **Navigation:** from 600 dp a `NavigationRail` on the left takes the place of
  the bottom bar, tabs centred vertically; the mini player stays at the bottom
  of the content. Tabs keep their own stacks (`selectTab`) either way.
- **Now playing:** wide windows put the cover on the left — sized by the
  height, at most 45 % of the width — and title, progress, controls, value bar
  and the sheet chips on the right, in a column that scrolls if it must and is
  no wider than 560 dp. Upright it stays the phone layout, at most 560 dp wide
  and centred (a tablet held upright); that column does not scroll, so swiping
  down still closes the player.
- **Pages of running text** — episode, settings, privacy, wallet, history,
  crash report — are centred and at most 720 dp wide (`Readable`), about 80
  characters a line. The tour's text column is at most 520 dp.
- **Tests:** `LargeScreenTest` drives the app at tablet landscape, tablet
  portrait, phone landscape and phone portrait and checks that the player's
  controls are on screen and where the tabs are. Three of its five tests fail
  on the layout before step 1.

### Step 2 — use the space

With JetBrains' multiplatform Material 3 Adaptive
(`org.jetbrains.compose.material3.adaptive`, 1.3.0 is built on Compose 1.10):

- `ListDetailPaneScaffold` for library → show and show → episode: list on the
  left, detail on the right from *expanded*;
- now playing as a supporting pane on the right from *expanded*, instead of a
  full-screen overlay (as Apple Podcasts does on the iPad);
- Home in two columns (New, Continue | Your shows, Take part) or centred;
  episode lists no wider than is useful;
- bottom sheets as dialogs or side sheets from *expanded*;
- keyboard and pointer for iPad and Mac: space for play/pause, arrows to skip,
  hover states, a context menu on right-click where a long press acts on
  phones.

### Step 3 — the Mac

Enable the iPad app on Apple-Silicon Macs in App Store Connect, check it as a
Mac window (resizing, media keys, Now Playing, the menu bar's standard items).
Compose Desktop only if listeners ask for a native Mac app.

## Consequences

- Hopp is usable on tablets, the iPad and a phone on its side from step 1;
  step 2 is about making good use of the space, not about whether it works.
- Before an iOS release that claims the iPad, check it on the iPad simulator
  in both orientations and in Split View, and add iPad screenshots.
- New screens are checked at the four sizes of `LargeScreenTest`
  (Robolectric `qualifiers`), not only at the phone's.
- The large-screen layout is one more code path in now playing and the frame;
  it lives in `ui/components/Adaptive.kt` (breakpoints, `Readable`) and in the
  two places that switch on the window's size.
