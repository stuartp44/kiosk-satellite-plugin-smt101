# SMT101 MQTT Sensors for Kiosk Satellite

`kiosk-satellite-plugin-smt101` is an SDK 1 Kiosk Satellite plugin that subscribes to the MQTT topics published by the SMT101 control app and republishes the incoming readings as Kiosk Satellite entities for local Readings and Home Assistant exposure.

## Purpose

The SMT101 Android application already publishes its hardware readings over MQTT. This plugin consumes that MQTT stream inside Kiosk Satellite instead of trying to read the wall panel hardware directly. The first functional version focuses on read-only ingestion for:

- temperature
- humidity
- light level
- digital input 1
- digital input 2

Relay control is intentionally not exposed yet. The source SMT101 app publishes relay state topics, but this plugin does not create writable switch entities until command handling and state confirmation can be implemented safely.

## Supported entities

When enabled, the plugin publishes these SDK 1 `entities` readings:

| Entity | Type | Topic suffix | Metadata |
| --- | --- | --- | --- |
| SMT101 Temperature | Numeric sensor | `sensor/temperature` | unit `°C`, device class `temperature`, state class `measurement`, 1 decimal |
| SMT101 Humidity | Numeric sensor | `sensor/humidity` | unit `%`, device class `humidity`, state class `measurement`, 0 decimals |
| SMT101 Light | Numeric sensor | `sensor/light` | unit `lx`, device class `illuminance`, state class `measurement`, 0 decimals |
| SMT101 Input 1 | Binary sensor | `input1/state` | read-only boolean |
| SMT101 Input 2 | Binary sensor | `input2/state` | read-only boolean |

The plugin starts each enabled entity with an unknown value and updates it whenever a matching MQTT message arrives.

## MQTT topics and payload parsing

The default topic prefix is `smt101`, so the plugin subscribes to:

- `smt101/sensor/temperature`
- `smt101/sensor/humidity`
- `smt101/sensor/light`
- `smt101/input1/state`
- `smt101/input2/state`

You can change the prefix to something like `floor1/smt101` and the same fixed suffixes are appended automatically.

### Accepted payload forms

The parser accepts plain values and common flat JSON objects so it can tolerate the formats documented by the SMT101 project:

- numeric strings such as `23.5`, `45`, `120`
- JSON objects such as `{"temperature":23.5}`, `{"humidity":45}`, `{"lux":120}`, `{"value":120}`
- boolean-like strings such as `ON`, `OFF`, `true`, `false`, `1`, `0`
- JSON objects such as `{"state":"ON"}`, `{"input":0}`, `{"value":true}`

Malformed or unsupported payloads are ignored, logged through `host.log(...)`, and do not crash the plugin or Kiosk Satellite.

## Configuration

The plugin manifest declares these settings:

| Setting | Type | Default | Notes |
| --- | --- | --- | --- |
| `mqttHost` | string | empty | Broker hostname or IP. Leaving this blank keeps the plugin idle. |
| `mqttPort` | number | `1883` | Falls back to `8883` when TLS is enabled and an invalid port is supplied. |
| `mqttUsername` | string | empty | Optional username. |
| `mqttPassword` | string | empty | Optional password. Stored by Kiosk Satellite like any other plugin setting. |
| `mqttTls` | boolean | `false` | Uses `ssl://` instead of `tcp://`. |
| `mqttClientId` | string | empty | Optional explicit client ID. If blank, the plugin generates a per-session ID. |
| `topicPrefix` | string | `smt101` | Prefix prepended to all supported SMT101 topic suffixes. Leading and trailing slashes are trimmed. |
| `enableEnvironmentSensors` | boolean | `true` | Enables temperature, humidity, and light entities. |
| `enableInputs` | boolean | `true` | Enables the two digital input entities. |

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

- The plugin connects asynchronously and retries after transient MQTT failures.
- Settings changes cause the connection and subscriptions to refresh.
- `host.status(...)` is used for connection state and errors.
- `host.log(...)` is used for ignored commands, ignored events, invalid payloads, and subscription diagnostics.
- `stop()` attempts to remove published entities and disconnect cleanly. Kiosk Satellite also removes plugin session entities when a session stops.

## Limitations and future work

- Relay topics are intentionally not exposed as switches in this first version.
- No shared Kiosk Satellite MQTT service is used; the plugin connects directly to the configured broker.
- TLS currently uses the platform default trust store and does not expose custom CA or client certificate settings.
- Only flat JSON objects and plain scalar payloads are parsed.
- The plugin does not publish Home Assistant MQTT discovery directly; it relies on Kiosk Satellite's native entity bridge.

## Licensing

This repository is licensed under [Apache-2.0](LICENSE).

Bundled dependency notes are included in [assets/licenses/THIRD_PARTY_NOTICES.txt](assets/licenses/THIRD_PARTY_NOTICES.txt). The only embedded runtime dependency is the Eclipse Paho MQTT Java client used for broker connectivity.
