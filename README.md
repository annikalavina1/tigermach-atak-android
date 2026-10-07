# Tigermach Android ATAK prototype

Work-in-progress Android ATAK plugin integrating ATAK data with ActiveLook eyewear over Bluetooth Low Energy. This is an Android integration prototype, separate from the iOS adaptive cueing demo. It is not a complete adaptive cueing system or a verified deployable build.

## Source components

- `PluginTemplate.java`: plugin entry point and user interface.
- `BleManager.java`: discovery, connection, and ActiveLook display communication.
- `CommandManager.java`: display command handling.
- `AtakDataProvider.java`: ATAK-derived heading and waypoint data.

## Build status

The supplied project targets ATAK 5.5.0 and declares Android Gradle Plugin 8.8.2. The accompanying Android Studio screenshot reports a failed Gradle sync. No successful build or device run has been verified for this upload.

The build expects the ATAK development plugin, supplied through a configured TAK repository or a local plugin JAR. Each developer must obtain authorized access to the required dependencies and configure their local environment. The supplied local configuration contains an Android SDK path but no TAK repository configuration. Follow `template.local.properties` as a configuration reference; keep real credentials in untracked local configuration.

Local SDK paths, IDE state, Gradle caches, generated builds, and bundled `.takdev` artifacts are omitted from this source upload. The Gradle wrapper is retained.

## Initial engineering tasks

1. Resolve Gradle sync and document a reproducible developer setup.
2. Validate plugin installation against the intended ATAK version.
3. Validate BLE connection and display commands on the target glasses.
4. Check data provenance: phone/ATAK heading is not a measurement of glasses orientation.
5. Keep future adaptive cue decisions separate from ATAK data ingestion and display rendering.

Signing passwords and key aliases are read from `TIGERMACH_STORE_PASSWORD`, `TIGERMACH_KEY_PASSWORD`, and `TIGERMACH_KEY_ALIAS` environment variables. No signing credentials or keystore are included. Configure signing locally before building a signed APK.
