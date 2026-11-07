# Agent VScreen

Android app (`com.kilon.agentvscreen`, v1.1.5) that gives AI agents a virtual screen on the phone: an on-device HTTP/MCP API, a `vscreen` CLI, and on-device OCR (PaddleOCR ONNX models).

## Build

Requirements: JDK 17, Android SDK (compileSdk 36), arm64 device, Android 11+.

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleRelease
```

Release signing is optional: copy `keystore.properties.example` to `keystore.properties` and fill it in. Without it the release build is signed with the debug key.

## License

MIT. The bundled OCR models (`app/src/main/assets/vscreen-ocr/`) come from PaddleOCR and keep their own Apache-2.0 license.
