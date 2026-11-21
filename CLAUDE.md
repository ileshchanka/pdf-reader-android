# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Minimal Android PDF reader: Kotlin + Jetpack Compose (Material 3), single module `:app`, package `com.example.pdfreader`, minSdk 24 / target 35. Rendering uses the platform `android.graphics.pdf.PdfRenderer` — no third-party PDF libraries.

## Localization

All UI strings live in resources and follow the system language: English is the default in `values/strings.xml`, and there are 16 more `values-<lang>/` folders (Indonesian uses the legacy code `in`, Chinese uses `zh-rCN`/`zh-rTW`). No user-visible text belongs in Kotlin code. Use `stringResource` in composables, or `context.getString` outside composition, e.g. error messages in `ReaderScreen`.
- When adding or renaming a string, update **every** `values-*` file. Format arguments are positional (`%1$d`, `%2$d`) because some languages reorder them.
- When adding a language, also add its code to `resourceConfigurations` in `app/build.gradle.kts`. That list filters out library translations (Compose/Material ship ~85 languages) and feeds the generated per-app language picker (`generateLocaleConfig`, Android 13+; `res/resources.properties` declares the default locale `en`).

## Build & run

The toolchain lives outside the project; `JAVA_HOME` must point to JDK 17 (the system `java` is Java 8 and will fail):

```
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/jdk-17.0.21+9/Contents/Home
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew assembleRelease      # R8-minified, signed with the debug key so it installs directly
./gradlew installRelease       # install on the connected device
```

Android SDK: `~/Library/Android/sdk` (referenced by the git-ignored `local.properties`); `adb` is in `platform-tools/`, and adb sometimes loses the USB device until `adb kill-server`. There are no tests or lint configuration, so verification means building and checking the APK, e.g. `~/Library/Android/sdk/build-tools/35.0.0/aapt2 dump badging app/build/outputs/apk/release/app-release.apk` (locales) or `aapt2 dump resources …` (string translations). Dependency versions are in `gradle/libs.versions.toml`.

The test device is the user's personal phone: only `adb install` on it. Don't launch the app, tap or take screenshots without asking, because the screen may show their private apps.

Commit messages: plain title and body, with no `Co-Authored-By`/`Claude-Session` trailers. The user removed them from history.

Releases (GitHub, via `gh`) are scripted in the project skill `.claude/skills/release/SKILL.md` (`/release`): refresh CLAUDE.md/README, bump `versionCode`/`versionName` in `app/build.gradle.kts`, update the version in the README's "Скачать APK" link (it points at the release asset `releases/download/vX.Y/pdf-reader-X.Y.apk`), build `assembleRelease`, commit and push, then `gh release create vX.Y <apk copied as pdf-reader-X.Y.apk> --title vX.Y` with short English notes ending in the debug-key/unknown-sources install note.

## Architecture

- `MainActivity.kt` — the only Activity; navigation is a single `openUri` state in `App()`: `null` → `HomeScreen`, otherwise → `ReaderScreen`. PDFs arrive either from the SAF picker (`OpenDocument`, persistable permission taken so recent files reopen after restart) or via the `ACTION_VIEW` intent-filter (`incomingUri`, also handled in `onNewIntent`). The manifest declares `configChanges` for rotation etc., so the Activity is **not** recreated — the open `PdfDoc` and scroll state survive rotation without saved-state plumbing.
- `PdfDoc.kt` — wrapper over `PdfRenderer`. PdfRenderer allows only one open page at a time and is not thread-safe, so every page access goes through a `Mutex`. Page sizes in points are read for all pages at open time, so placeholders have the right aspect before rendering. Rendered bitmaps go to an `LruCache` keyed by `"index:width:cropRect"` and sized at 1/4 of max heap (`largeHeap` is enabled). Bitmaps are capped at 6 MP, since narrow cropped pages get tall. `close()` is async and waits for the in-flight render.
- `ReaderScreen.kt` — reader UI, always in a dark color scheme.
  - **Fullscreen** (`FullscreenEffect`): `WindowInsetsControllerCompat` hide/show of system bars with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`; single tap toggles it, Back exits it first. The app is edge-to-edge (`enableEdgeToEdge` + `SHORT_EDGES` cutout mode in `MainActivity`). List content padding uses `systemBarsIgnoringVisibility` so the layout doesn't jump when bars hide.
  - **Zoom** (`PdfPages`): zoom changes the real `LazyColumn` width rather than scaling an image, so pages re-render sharply. Horizontal scroll is custom (`offsetX` + `scrollable` + `Modifier.layout` placement), not `horizontalScroll`, so its position can be set in the same frame as a new zoom. `applyZoom` keeps the document point under the fingers fixed: it sets `offsetX`, computes the vertical position from the page under the fingers (page heights at the new zoom are known exactly from `displayAspect`) and calls `listState.requestScrollToItem`, so zoom and both scroll positions land in one frame. A pinch calls `applyZoom` every frame (live relayout, no `graphicsLayer`), so zooming out reveals neighbouring pages. During the pinch the render width is frozen, so bitmaps are just stretched, and pages re-render after release. Use `calculateZoom`/`calculatePan`/`calculateCentroid(useCurrent = false)`: they only count pointers that were down in the previous event too, so a finger landing or lifting causes no jump. The pinch detector runs in `PointerEventPass.Initial` and consumes multi-touch events so the list doesn't scroll with two fingers. Render widths are rounded up to multiples of 256 px and capped at 2048 px (`MAX_RENDER_WIDTH`).
  - Current page = the item crossing the viewport center; it is persisted on every change.
  - **Toolbar menu** (`ReaderMenu`): every action lives in one burger `DropdownMenu` (share, print, crop and night-mode toggles with a check mark).
  - **Share**: sends the original `content://` URI via `ACTION_SEND` + chooser with `FLAG_GRANT_READ_URI_PERMISSION` (no copy, no FileProvider). The menu item is hidden for `file://` URIs (`FileUriExposedException` on API 24+).
  - **Print** (`Print.kt`): `PrintManager` with a `PrintDocumentAdapter` that copies the original PDF bytes to the print destination on a background thread (no re-rendering). Needs the Activity context.
  - **Margin crop** (menu toggle, saved per file): horizontal only. `PdfDoc.probeBounds` renders a page at 300 px and finds the horizontal content span, trimming 0.5% of the ink mass from each edge (specks, lone page numbers). `prepareCrop()` samples up to 40 pages and takes the 25th/75th percentile of left/right bounds separately for odd/even pages, so text scale stays constant across pages. The sampling runs once per document in the background right after opening (`prepareCrop()` returns a shared `Deferred` in the doc's own scope, cancelled by `close()`), so the toggle is usually instant; the centered `CropProgress` card (driven by `PdfDoc.cropProgress`) appears only when the user turns crop on before that job has finished. Each page's own bounds are unioned in at render time, so content is never clipped. A cropped page is rendered with a `Matrix` passed to `page.render`. `PdfDoc.cropVersion` is Compose state: `displayAspect`/`cropKey` read it, so pages re-layout and re-render when bounds become known.
- `Prefs.kt` — SharedPreferences: recent files list (JSON array, max 20, most recent first; a file is moved to the front when `ReaderScreen` opens it and removed if it fails to open), last page per URI, night mode (night mode = color-inversion `ColorFilter` on page images). Removing a recent file also drops its page key, and `MainActivity` releases its persistable URI permission.
