# Tigermach Android ATAK prototype

Work-in-progress Android ATAK plugin integrating ATAK data with ActiveLook eyewear over Bluetooth Low Energy. This is an Android integration prototype, separate from the iOS adaptive cueing demo. It is not a complete adaptive cueing system or a verified deployable build.

## Source components

- `PluginTemplate.java`: plugin entry point and user interface.
- `BleManager.java`: discovery, connection, and ActiveLook display communication.
- `CommandManager.java`: display command handling.
- `AtakDataProvider.java`: ATAK-derived heading and waypoint data.

## Build status and setup

On October 7, 2026, Annika's local project successfully synced using the ATAK CIV 5.5.0.7 offline SDK and completed `assembleCivDebug`, producing a debug APK. Installation, ATAK plugin loading, and BLE/display behavior have not been validated. The public repository uses environment-based signing configuration rather than the local project's signing credentials; a clean checkout has not yet been independently built.

The project targets ATAK 5.5.0, Android Gradle Plugin 8.8.2, and Gradle 8.13. Install Android SDK platform 35 and use a compatible JDK (the source targets Java 17). Select your local Gradle JDK in Android Studio; machine-specific JDK paths must not be committed.

1. Obtain the matching ATAK CIV development SDK through an authorized source and extract it outside this repository. Its root should contain `atak-gradle-takdev.jar`, `main.jar`, and `android_keystore`.
2. Copy `template.local.properties` to `local.properties` and replace all three example paths. `sdk.dir` is the Android SDK; `sdk.path` is the ATAK SDK directory; `takdev.plugin` is the full path to its plugin JAR. Paths may contain spaces; do not wrap property values in quotes. For this offline setup, leave `takrepo.url`, `takrepo.user`, `takrepo.password`, and `takrepo.force` unset, including in user-level Gradle properties.
3. Set `TIGERMACH_STORE_PASSWORD`, `TIGERMACH_KEY_PASSWORD`, and `TIGERMACH_KEY_ALIAS` in your local environment using the matching development keystore's credentials. Android Studio must inherit those variables if building through the IDE. No passwords, signing keys, or SDK binaries are included in this repository.
4. Sync the project. The output should say `Configuring Offline TakDev plugin build`. Missing remote dependencies and skipped CIV variants indicate an incomplete setup even if sync finishes.
5. From the repository root, build with:

```bash
bash ./gradlew assembleCivDebug
```

The APK appears under `app/build/outputs/apk/civ/debug/`. Connected tests may be skipped when the local test setup is unavailable; this is not a device-test result. Missing Git metadata in ZIP downloads affects version naming and can trigger a fallback revision. Gradle deprecation warnings remain; do not upgrade the toolchain solely to silence them before checking ATAK compatibility.

`Plugin with id 'atak-takdev-plugin' not found` means the plugin JAR is unavailable at the configured path. Remote-resolution warnings are addressed by pointing `sdk.path` at the directory containing `main.jar`, rather than only configuring the plugin JAR.

Local SDK paths, IDE state, Gradle caches, generated builds, signing keys, and bundled `.takdev` artifacts are omitted from the source upload. The Gradle wrapper is retained.

## Initial engineering tasks

1. Confirm a clean checkout builds with the documented SDK and local signing setup.
2. Validate plugin installation against the intended ATAK version.
3. Validate BLE connection and display commands on the target glasses.
4. Check data provenance: phone/ATAK heading is not a measurement of glasses orientation.
5. Keep future adaptive cue decisions separate from ATAK data ingestion and display rendering.

Signing passwords and key aliases are read from `TIGERMACH_STORE_PASSWORD`, `TIGERMACH_KEY_PASSWORD`, and `TIGERMACH_KEY_ALIAS` environment variables. No signing credentials or keystore are included. Configure signing locally before building a signed APK.
