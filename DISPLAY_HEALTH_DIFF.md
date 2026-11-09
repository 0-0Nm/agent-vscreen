# Display-health follow-up diff

Base: remote `main` at `8220279df3e73d5e75989496b9489ebe3d109d66`.

## Corrective changes

- `BackgroundOperation.agentAction` performs generation, re-read, user-stop and takeover checks before `ensureForAction`; `actionStarted` is recorded only after the wake gate. This ordering is deliberate, not an atomic race-free guarantee.
- `VScreenDisplay` publishes the bitmap, version, conversion time, and producer receipt time as one synchronized sample. Freshness is based on conversion/publication after the request; the code does not claim `Image.timestamp` provenance.
- `snapshot` consumes that exact sample instead of re-reading `latestWithVersion`, so OCR, metadata, and image use the same frame.
- Reports distinguish no frame from a cached frame; no new frame while static remains cached/unconfirmed and is not an automatic scene failure.
- Errors say the requested touch/input/business key did not execute while allowing that the safety gate may have sent `KEYCODE_WAKEUP` internally.
- Targeted test XML/report files are uploaded with `if: always()` before the build steps.

## Evidence boundary

Local `git diff --check` passed. Local targeted Gradle execution was blocked by the missing Gradle 8.11.1 distribution and a cached Gradle 9.4.1 attempt exceeded the tool request budget; cloud test/build evidence is required. No device install or APK publication was performed locally.
