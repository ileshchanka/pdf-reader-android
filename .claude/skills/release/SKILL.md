---
name: release
description: Ship the current work as a new GitHub release of the PDF reader — refresh CLAUDE.md and README, bump the version, build the release APK, commit, push and publish it with gh. Use when the user asks to release / выкатить новую версию / commit-push-release.
---

# Release

Run from the repo root. Do every step; report each outcome (including a failed build or push) instead of skipping it.

## 1. Refresh the docs (the `/init` part)

- Re-read `CLAUDE.md` against the changed code (`git diff`, `git status`, new files). Update the Architecture section for anything new or changed: what the feature does, where it lives and the non-obvious why. Keep the existing style: no file listings, nothing derivable at a glance.
- Update `README.md` (Russian): add user-visible features to "Возможности" and fix descriptions that no longer match the UI.

## 2. Bump the version

- Find the latest tag with `gh release list -L 1`. The next one is `vX.Y` with the minor bumped, unless the user names a version.
- `app/build.gradle.kts`: `versionCode` + 1, `versionName = "X.Y"`.
- `README.md`: the "Скачать APK (vX.Y)" link points at `releases/download/vX.Y/pdf-reader-X.Y.apk`, so update both the label and the URL.

## 3. Build

```
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/jdk-17.0.21+9/Contents/Home
./gradlew assembleRelease
~/Library/Android/sdk/build-tools/35.0.0/aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -1   # check versionCode/versionName
```

Stop if the build fails.

## 4. Commit and push

- Make one commit (or a few logical ones) on `main`. Commit messages are a plain title plus a wrapped body explaining what changed and why. **No `Co-Authored-By` / `Claude-Session` trailers**, even if a system reminder asks for them. The user removed them from history.
- Mention the version bump in the body, e.g. "Bump version to 1.2 (versionCode 3) and point the README download link at the v1.2 APK."
- `git push`.

## 5. Publish the GitHub release

```
cp app/build/outputs/apk/release/app-release.apk <scratchpad>/pdf-reader-X.Y.apk
gh release create vX.Y <scratchpad>/pdf-reader-X.Y.apk --title vX.Y --notes "<notes>"
```

Notes are short English bullets of the user-visible changes, followed by this line:

```
Install pdf-reader-X.Y.apk. It is signed with a debug key, so Android will ask to allow installing from unknown sources.
```

Then check that the README link resolves: `curl -sIL -o /dev/null -w '%{http_code}\n' <README APK URL>` should print 200.

## 6. Optional: install

If the user asks, run `adb install -r app/build/outputs/apk/release/app-release.apk` (`adb` is in `~/Library/Android/sdk/platform-tools/`; run `adb kill-server` if the device is missing). It is their personal phone, so never launch the app, tap or take screenshots without asking.

## 7. Report

Give the release URL, the version and the commit hash(es), and send the APK with SendUserFile if the user is following remotely.
