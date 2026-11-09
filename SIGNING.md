# ATVSX fixed signing

Cloud APK assembly requires the repository Actions Secrets ATVSX_SIGNING_KEYSTORE_BASE64, ATVSX_SIGNING_STORE_PASSWORD, ATVSX_SIGNING_KEY_PASSWORD and ATVSX_SIGNING_KEY_ALIAS. Their values must never be committed. The workflow decodes the keystore only under runner.temp with restrictive permissions, removes it in an always step, and verifies the APK signer against the public fingerprint below. Missing secrets stop assembly instead of silently falling back to a random debug certificate.

Public certificate SHA-256: b0a09b4abb0df7196d83b83ed158c909771ae562fe28b55abdcae21938fc9900

The debug variant remains debuggable; a persistent certificate does not make it a production/release build. Future debug/release artifacts must keep this identity for upgrades. Previously installed transient-debug-signed APKs are NOT automatically compatible. Do not uninstall or clear app data automatically to solve a signature mismatch.

Keep an independent offline backup of the PKCS12 keystore and credentials. Git history and APKs do not restore the private key; GitHub Secrets cannot be read back as a backup. No signing material is stored in this repository.
