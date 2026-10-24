# Release signing

`.github/workflows/android.yml` signs every release APK built from `main` with one release key,
so each new APK installs over the previous one (`adb install -r`, or tapping it in Chrome) and
keeps the app's Room database and Keystore-backed API keys.

The key lives only in four repository secrets. **Keep your own copy of the keystore and its
passwords.** If the key is lost, no later build can be installed over earlier ones: users would
have to uninstall, which deletes their data.

## One-time setup

Run on your own machine (JDK 17 or later), from a directory outside the repository:

```bash
keytool -genkeypair -v -keystore visionstudio-release.jks -storetype PKCS12 \
  -alias visionstudio -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=VisionStudio AI"
# keytool prompts for a password; PKCS12 uses the same one for the store and the key.

gh secret set VISIONSTUDIO_KEYSTORE_BASE64 -R tapash250/visionstudio_ai < <(base64 < visionstudio-release.jks | tr -d '\n')
gh secret set VISIONSTUDIO_KEYSTORE_PASSWORD -R tapash250/visionstudio_ai   # paste the password
gh secret set VISIONSTUDIO_KEY_PASSWORD -R tapash250/visionstudio_ai        # same password
gh secret set VISIONSTUDIO_KEY_ALIAS -R tapash250/visionstudio_ai --body visionstudio
```

Then run **Android CI** from the Actions tab (*Run workflow* on `main`). The run summary shows the
signing certificate's SHA-256; it should be identical on every later run.

## Downloads

- Plain APK (one tap in Chrome on the phone):
  `https://github.com/tapash250/visionstudio_ai/releases/download/latest-main/visionstudio-release.apk`
- Per-run artifact `visionstudio-release-apk` on each workflow run (downloaded as a zip).

## Local release builds

Set the same variables to sign a local build with the release key:

```bash
export VISIONSTUDIO_KEYSTORE_FILE=/path/to/visionstudio-release.jks
export VISIONSTUDIO_KEYSTORE_PASSWORD=... VISIONSTUDIO_KEY_PASSWORD=... VISIONSTUDIO_KEY_ALIAS=visionstudio
./gradlew :app:assembleRelease
```

Without them, `assembleRelease` signs with your machine's debug key, as before.
