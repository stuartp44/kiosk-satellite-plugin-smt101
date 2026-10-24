# SMT101x Sensors for Kiosk Satellite

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=stuartp44/kiosk-satellite-plugin-smt101)

Bring your SMT101 tablet's built-in sensors and controls into Kiosk Satellite.
See temperature, humidity, and distance readings, check door inputs, and
control the relays and RGB lighting on supported models.

Everything can run locally on the tablet, without a separate MQTT server.
The plugin can also help keep the tablet's built-in MQTT service running,
whether you use it with Kiosk Satellite or an existing MQTT setup.

For firmware identifiers, MQTT topics and payloads, implementation details,
serial/IR investigation results, and build instructions, see
[Technical details](docs/TECHNICAL.md).

## What the plugin does

| Entity | Feature |
| --- | --- |
| Temperature | Temperature reading in degrees Celsius |
| Humidity | Relative humidity reading in percent |
| Distance | Distance reading in millimetres, on equipped units |
| Switch 1 / Switch 2 | Control the tablet's Relay 1 / Relay 2 outputs |
| Door 1 / Door 2 | Door input states |
| RGB Backlight | On/off, brightness, and RGB color controls |

Entities appear only after the tablet publishes a valid reading or status
for that feature. Not every tablet has or publishes every feature.
The display backlight is separate from the RGB light and is not controlled
by this plugin.

The plugin can optionally restart a stopped OEM MQTT publisher. It can also
be used solely for that recovery function with an existing external MQTT
server, without creating local Kiosk Satellite entities.

**Serial ports (RS232/RS485) and IR are not exposed by the current plugin.**
Investigation notes describe possible future work, not available features.

## Compatibility

The plugin has been checked on SMT1019-R157 V1.0A and V2.0 firmware.
Automatic recovery is restricted to the two exact builds listed in
[Known devices](docs/TECHNICAL.md#known-devices).

On V2.0, temperature, humidity, distance, and publisher recovery have been
verified. Switch and door status publications have been observed, but
physical input changes, relay commands, and RGB operation remain unverified.
Other SMT101-family devices and firmware are not currently confirmed.

## Installation

1. Use the install button above, or download a plugin ZIP from
   [GitHub Releases](https://github.com/stuartp44/kiosk-satellite-plugin-smt101/releases)
   and install it through Kiosk Satellite's Plugin Manager.
2. Choose one of the operating modes below and configure the tablet and plugin.
3. For local mode, confirm that real sensor readings appear in Kiosk Satellite
   and that the plugin log reports an MQTT client connection and publications.

When testing a local ZIP on a fleet-managed tablet, disable plugin
synchronization first; otherwise the leader can replace it with the
configured release.

## Operating modes

### Local sensor management through Kiosk Satellite

Turn **Host MQTT capture broker on this tablet** on and configure the tablet's
MQTT server as `127.0.0.1`, port `1883`. The plugin receives sensor and door
readings and exposes switch and RGB controls through Kiosk Satellite.
MQTT runs internally on the tablet; no separate MQTT server is required.

#### Tablet MQTT settings

Open **Android Settings > System > MQTT**. On the confirmed firmware,
tapping the MQTT row while enabled turns the publisher off; tapping it
again opens the **Set Mqtt** configuration dialog. This briefly interrupts
sensor publications. If automatic re-enabling is enabled, turn that plugin
option off temporarily so recovery does not interfere with configuration.

| Field | Value |
| --- | --- |
| host | `127.0.0.1` |
| port | `1883` |
| username | Leave blank |
| password | Leave blank |
| client_id | Keep your tablet's client ID, such as `office` |
| keepalive | `30` |

![Local MQTT configuration dialog captured from the SMT101x tablet](docs/images/local-mqtt-settings.png)

Tap **OK** to save and enable the OEM MQTT publisher. Ensure the MQTT switch
is on when finished. The switch indicates that MQTT is enabled, not that
sensor publications are reaching the plugin.

#### Plugin settings

| Setting | Local-mode value |
| --- | --- |
| **Host MQTT capture broker on this tablet** | **On** |
| **MQTT client ID (optional)** | Leave blank for automatic discovery, or match the tablet's `client_id` |
| **Automatically recover stopped OEM MQTT publisher** | **On** for unattended recovery |
| **Automatically re-enable disabled tablet MQTT** | Optional: **On** if recovery should also turn the Android MQTT switch back on |

Restore automatic re-enabling after configuration if you want that behavior.
Leave it off to respect intentional MQTT disables.

### External MQTT recovery

Turn **Host MQTT capture broker on this tablet** off and enable
**Automatically recover stopped OEM MQTT publisher**. Keep the server
address, port, credentials, and client ID already configured in
**Android Settings > System > MQTT**.

The plugin checks the OEM process once per minute and starts it if stopped.
This can recover crashes triggered by Wi-Fi interruptions or updates that
stop the publisher, once the configured server is reachable again.
It does not restart a running but disconnected process, confirm external
broker delivery, or repair Wi-Fi, authentication, or server failures.
The OEM client remains responsible for its own reconnect behavior.

External readings and commands continue through your existing MQTT setup;
they are not bridged into Kiosk Satellite by this plugin in this mode.

Changing modes does not rewrite the tablet's MQTT connection settings.
Configure the correct server address in Android settings when switching.

## Configuration reference

| Setting | Default | What it does |
| --- | --- | --- |
| **Host MQTT capture broker on this tablet** (`enableEmbeddedMqttBroker`) | On | Use the tablet's sensors and controls in Kiosk Satellite without a separate MQTT server. Set the server to `127.0.0.1` and port to `1883` in Android's MQTT settings. Turn this off to keep using your own server; recovery can still be used, but no local entities are created. Android's settings are not changed for you. |
| **MQTT client ID (optional)** (`mqttClientId`) | Blank | Usually leave this blank so the plugin learns the tablet's ID from its status updates. If you enter an ID, copy `client_id` from Android's MQTT settings exactly, such as `office`. This is used in local mode and does not change the tablet's ID. Do not use MQTT wildcards (`+` or `#`); leading/trailing slashes are removed. |
| **Automatically recover stopped OEM MQTT publisher** (`enableAutomaticMqttRecovery`) | Off | Restart the tablet's built-in MQTT service if it stops, so readings can resume. Works in either mode on supported firmware. It does not fix Wi-Fi, server problems, or a service that is running but disconnected. MQTT stays switched off unless automatic re-enabling is also enabled. |
| **Automatically re-enable disabled tablet MQTT** (`reenableDisabledMqtt`) | Off | Turn the tablet's MQTT switch back on if it has been disabled. Requires automatic recovery and works in either mode. Leave this off if you want to be able to turn MQTT off yourself. Your server address and login details stay unchanged; turning MQTT on does not guarantee delivery of readings. |

## Important behavior and limitations

- **Last MQTT message:** A compact chart on the plugin settings page shows the date and time of
  the latest MQTT publication received by the local broker, using the tablet's
  time zone. Before the first message it shows `Waiting for first message`.
  Any publication counts, even if its topic or payload is not a supported
  sensor reading. This resets when the plugin restarts or its settings change.
  This diagnostic stays in the plugin page; it is not an ESPHome or Home
  Assistant entity. The chart retains only the latest message, not a history.
  External MQTT mode shows `Not monitored (external MQTT server)` because
  the plugin cannot see messages sent to your own server.
- **Relay safety:** Switch 1 and Switch 2 control physical relay outputs.
  Their connected loads, contact types, and electrical ratings are not
  established by this project. Check your device documentation and wiring.
  Commands operate immediately without confirmation; keep unsafe or
  unverified loads disconnected while testing.
- **RGB on connection:** In local mode, the plugin turns the RGB light off
  if its first valid power status after an OEM MQTT connection is `ON`.
  This applies on reconnects too. Later power changes are left alone.
  A user RGB command before that first report cancels the check.
  External recovery mode does not control the RGB light.
- **Stale readings:** Temperature, humidity, and distance readings become
  `Unknown` after 30 minutes without an update. Door and switch states do
  not expire because the OEM publisher may report them only when they change.
- **Settings changes:** Existing local entities are removed and reappear
  after their next valid publication.
- **Recovery:** Checks run once per minute; restart attempts and failed
  checks have a five-minute cooldown. Recovery leaves running publishers
  and intentionally disabled MQTT unchanged unless re-enabling is enabled.
  Turn re-enabling off before intentionally disabling tablet MQTT.
- **After reboot:** Recovery resumes when Kiosk Satellite starts the enabled
  plugin. Configure Kiosk Satellite to launch on boot if needed.
- **Disabling recovery:** Stops new checks but may not cancel an operation
  already submitted. Disabling the plugin does not stop the OEM publisher.
- **Diagnostics:** Ten minutes without MQTT publications produces a status
  warning. Check the plugin status and log for connection, command, broker,
  or recovery failures. See [technical diagnostics](docs/TECHNICAL.md#entity-state-refresh-and-diagnostics)
  for detailed behavior.

## Disclaimer

This is an independent community plugin, not affiliated with, endorsed by,
or supported by Apolosign or the manufacturers of SMT101-family devices
and their OEM firmware.

This plugin is provided "as is" and without warranties of any kind, to the
maximum extent permitted by applicable law. Use it at your own risk. You are
responsible for deciding whether it is suitable for your device and setup,
reviewing its settings, and testing its behavior. The plugin can interact
with device MQTT services and controls; do not rely on it for safety-critical
or other essential functions. To the maximum extent permitted by applicable
law, the author is not liable for damages or losses arising from use of the
plugin. Your statutory rights are not affected. See the Apache-2.0
[LICENSE](LICENSE) for the full warranty disclaimer and limitation of liability.
