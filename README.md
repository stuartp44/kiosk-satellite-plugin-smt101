# SMT101 Direct Sensors for Kiosk Satellite

`kiosk-satellite-plugin-smt101` is an SDK 1 Kiosk Satellite plugin that reads SMT101 temperature and humidity directly from device input events and republishes those readings as Kiosk Satellite entities for local Readings and Home Assistant exposure through Kiosk Satellite's native entity bridge.

## Purpose

Phase 1 focuses on direct, read-only ingestion for:

- temperature
- humidity

Light, inputs, relays, and LED control are intentionally not exposed in this phase. The direct hardware path is currently limited to the two values that the upstream SMT101 app derives from `getevent -l`.

## Supported entities

When enabled, the plugin publishes these SDK 1 `entities` readings:

| Entity | Type | Direct source | Metadata |
| --- | --- | --- | --- |
| SMT101 Temperature | Numeric sensor | `getevent -l` `event<ths>` `ABS_THROTTLE` | unit `°C`, device class `temperature`, state class `measurement`, 1 decimal |
| SMT101 Humidity | Numeric sensor | `getevent -l` `event<hum>` `001d` | unit `%`, device class `humidity`, state class `measurement`, 0 decimals |

The plugin starts each enabled entity with an unknown value and updates it whenever a matching input-event line arrives.

## Direct hardware behavior

At runtime the plugin:

- starts `getevent -l`
- reads the Android system properties used by the SMT101 app:
  - `com.gulukai.ths`
  - `com.gulukai.hum`
- falls back to configured event device numbers if those properties are unavailable
- parses the same event patterns used by the upstream SMT101 application:
  - temperature: `ABS_THROTTLE`
  - humidity: `001d`

This keeps phase 1 aligned with the direct approach used in `haade_panel_s504` without requiring MQTT.

## Configuration

The plugin manifest declares these settings:

| Setting | Type | Default | Notes |
| --- | --- | --- | --- |
| `temperatureEventDevice` | number | `7` | Fallback event device for temperature when property lookup fails. |
| `humidityEventDevice` | number | `8` | Fallback event device for humidity when property lookup fails. |
| `temperaturePropertyName` | string | `com.gulukai.ths` | System property used to discover the temperature event device. |
| `humidityPropertyName` | string | `com.gulukai.hum` | System property used to discover the humidity event device. |
| `geteventCommand` | string | `getevent -l` | Shell command used to stream sensor events. |

## Build and test

Requirements:

- JDK 17+
- Gradle 9+
- Android SDK with at least one installed platform and build-tools `d8`
- `ANDROID_HOME` or `ANDROID_SDK_ROOT` pointing at that SDK

Run the targeted unit tests:

```bash
gradle test
```

Build the package ZIP and checksum:

```bash
gradle clean build -PandroidPlatform=35
```

The build output is written to `dist/`:

- `kiosk-satellite-plugin.json`
- `smt101-sensors-<version>.zip`
- `smt101-sensors-<version>.zip.sha256`

The ZIP root contains:

- `kiosk-satellite-plugin.json`
- `plugin.jar` (DEX payload for Kiosk Satellite)
- `LICENSE`
- `assets/licenses/THIRD_PARTY_NOTICES.txt`

For release builds, pass `-PpluginVersion=1.2.3` so the packaged manifest and ZIP filename use the release version without editing the source manifest.

## Packaging and installation

1. Build the ZIP locally or let GitHub Actions build it from a release tag.
2. In Kiosk Satellite, enable **Plugin Manager**.
3. For developer testing, use **Plugin Manager > Developer Tools > Install from ZIP** and choose the file from `dist/`.
4. For normal repository installation, publish a stable GitHub release and use the repository URL from Kiosk Satellite's **Add plugin** flow.

## GitHub Actions workflow

`.github/workflows/build.yml` runs the unit tests, builds the package ZIP, uploads the generated artifacts, and attaches the manifest, ZIP, and checksum to published GitHub releases.

## Runtime behavior

- The plugin starts a background `getevent` reader asynchronously.
- Settings changes stop the current reader and start a fresh one.
- `host.status(...)` is used for startup, running, and restart/error messages.
- `host.log(...)` is used for reader diagnostics.
- `stop()` removes entities and stops the active shell reader process.

## Limitations and future work

- This phase only supports temperature and humidity.
- It depends on the SMT101-specific `getevent` output format and system properties used by the upstream app.
- It assumes `getevent` and `getprop` are available on the kiosk device.
- It does not use vendor JNI libraries or direct Android `SensorManager` integration yet.
- It does not publish Home Assistant MQTT discovery directly; it relies on Kiosk Satellite's native entity bridge.

## Licensing

This repository is licensed under [Apache-2.0](LICENSE).

Runtime dependency notes are included in [assets/licenses/THIRD_PARTY_NOTICES.txt](assets/licenses/THIRD_PARTY_NOTICES.txt).
