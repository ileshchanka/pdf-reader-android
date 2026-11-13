# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Minimal Android PDF reader: Kotlin + Jetpack Compose (Material 3), single module `:app`, package `com.example.pdfreader`, minSdk 24 / target 35. Rendering uses the platform `android.graphics.pdf.PdfRenderer` — no third-party PDF libraries. UI strings are hardcoded Russian in Compose code (only `app_name` is in `strings.xml`).

## Build & run

The toolchain lives outside the project; `JAVA_HOME` must point to JDK 17 (the system `java` is Java 8 and will fail):

```
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/jdk-17.0.21+9/Contents/Home
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew assembleRelease      # R8-minified, signed with the debug key so it installs directly
./gradlew installRelease       # install on the connected device
```

Android SDK: `~/Library/Android/sdk` (referenced by the git-ignored `local.properties`); `adb` is in `platform-tools/`. There are no tests or lint configuration. Dependency versions are in `gradle/libs.versions.toml`.

## Architecture

- `MainActivity.kt` — the only Activity; navigation is a single `openUri` state in `App()`: `null` → `HomeScreen`, otherwise → `ReaderScreen`. PDFs arrive either from the SAF picker (`OpenDocument`, persistable permission taken so "Continue" works after restart) or via the `ACTION_VIEW` intent-filter (`incomingUri`, also handled in `onNewIntent`). The manifest declares `configChanges` for rotation etc., so the Activity is **not** recreated — the open `PdfDoc` and scroll state survive rotation without saved-state plumbing.
- `PdfDoc.kt` — wrapper over `PdfRenderer`. PdfRenderer allows only one open page at a time and is not thread-safe, so every page access goes through a `Mutex`. Page aspect ratios are read for all pages at open time (used for placeholders). Rendered bitmaps go to an `LruCache` keyed by `"index:width"` sized at 1/4 of max heap (`largeHeap` is enabled). `close()` is async and waits for the in-flight render.
- `ReaderScreen.kt` — reader UI, always in a dark color scheme.
  - **Fullscreen** (`FullscreenEffect`): `WindowInsetsControllerCompat` hide/show of system bars with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`; single tap toggles it, Back exits it first. The app is edge-to-edge (`enableEdgeToEdge` + `SHORT_EDGES` cutout mode in `MainActivity`). List content padding uses `systemBarsIgnoringVisibility` so the layout doesn't jump when bars hide.
  - **Zoom** (`PdfPages`): zoom changes the real `LazyColumn` width (inside a `horizontalScroll`) rather than scaling an image, so pages re-render sharply. During a pinch only a temporary `graphicsLayer` scale is applied; on release `commitZoom` sets the new zoom and re-adjusts horizontal/vertical scroll to keep the focal point. The pinch detector runs in `PointerEventPass.Initial` and consumes multi-touch events so the list doesn't scroll with two fingers. Render widths are rounded up to multiples of 256 px and capped at 2048 px (`MAX_RENDER_WIDTH`).
  - Current page = the item crossing the viewport center; it is persisted on every change.
- `Prefs.kt` — SharedPreferences: last opened file, last page per URI, night mode (night mode = color-inversion `ColorFilter` on page images).
