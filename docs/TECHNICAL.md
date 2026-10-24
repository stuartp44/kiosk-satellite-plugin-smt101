# SMT101x technical details

This document covers firmware verification, MQTT protocols, recovery internals,
serial and IR investigations, and development tooling. For the plugin's features,
installation, settings, and day-to-day use, see the [plugin README](../README.md).

## Known devices

| Firmware/build | Identification | Verification |
| --- | --- | --- |
| `ZX-SMT1019-R157-V1.0A-10.1-GG-K1.29U-20260707` | SMT1019-R157 V1.0A | Original confirmed hardware, including automatic OEM MQTT recovery |
| `ZX-SMT1019-R157-V2.0-10.1-GG-K2.15U-20260916` | SMT1019-R157 V2.0; Android model `rk3576_u`, product/device `WF2489T`, manufacturer `Electron`, brand `Apolosign`; Android 14 / API 34 | Identified through ADB on 13 October 2026; automatic OEM MQTT recovery and resumed sensor publications verified on 17 October 2026 |

The V2.0 unit uses the same observed sensor MQTT format. Inspection on
17 October 2026 confirmed the same OEM recovery Binder protocol and MQTT
init-service start trigger, so both exact builds above are allowlisted for
automatic recovery. RGB, switch, and door support on this variant is not
fully hardware-tested; switch and door status publications were observed
after recovery, but physical input changes, switch commands, and RGB
operation remain unverified. Entities are discovered only when their valid
status publications arrive.

Subsequent interactive testing confirmed RGB command delivery, but warning
flashes have shown intermittent instability. Automated effect tests verify
command sequences and reported state, not physical timing or long-term stability.

On the V2.0 tablet, the patched plugin restarted an enabled but stopped
publisher through the OEM service on 17 October 2026. Temperature, humidity,
and distance readings resumed. When testing a local ZIP on a fleet-managed
tablet, disable plugin synchronization first; otherwise the leader can
replace the local build with its configured release.

Other SMT101-family devices or firmware versions may use different MQTT
topics, payloads, or hardware interfaces and are not currently confirmed.

## Sensor sources and metadata

| Entity | Source | Metadata |
| --- | --- | --- |
| Temperature | OEM MQTT publication | `°C`, temperature, measurement, 1 decimal |
| Humidity | OEM MQTT publication | `%`, humidity, measurement, 0 decimals |
| Distance | OEM MQTT publication (on equipped units) | `mm`, distance, measurement, 0 decimals |
| Switch 1 / Switch 2 | OEM MQTT status and command topics | Relay 1 / Relay 2 outputs |
| Door 1 / Door 2 | OEM MQTT status publications | Binary sensors with the `door` device class |
| RGB Backlight | Built-in MQTT client command and state topics | On/off, brightness, RGB color, and plugin-generated effects |

The plugin does not inspect `/dev/input`.
Optional automatic recovery uses the tablet's exported
`com.broadcastinterface.ApiSystemService` to start the stopped built-in MQTT client.

## Automatic OEM publisher recovery

The confirmed firmware declares `iot_mqtt_client` as a disabled, `oneshot`
Android init service. It does not automatically restart after a crash, even
when `persist.start.mqtt` remains `1`. This leaves sensors `Unknown` while
the plugin's broker is still listening.

To enable recovery, enable **Automatically recover stopped built-in MQTT
client** in the plugin page. Capture continues to work with recovery
disabled. After reboot, recovery resumes when Kiosk Satellite starts the
enabled plugin; ensure Kiosk Satellite is configured to launch on boot.

The plugin checks the service once per minute. Local mode waits until the
embedded broker is listening. The broker is always enabled; saved
`enableEmbeddedMqttBroker=false` settings are ignored.
On the confirmed firmware only, when tablet MQTT is enabled
(`persist.start.mqtt=1`) and the service is `stopped`, it reapplies
the MQTT start property through the OEM Binder service's
`setSystemProperties` operation. Android init starts the OEM service with its
normal identity; the plugin does not launch a daemon itself.
It verifies that the service is running after two seconds and logs that it
is waiting for publications.
Actual sensor readings still come exclusively
from MQTT, not from the restart result.
Once a real MQTT publication arrives, the recovery status changes to
`MQTT publications are being received`. Publications arriving before the
restart confirmation are also recognized, so the confirmation cannot
overwrite active reception with a stale waiting message.

Running clients are left unchanged. When automatic re-enabling is disabled,
disabled tablet MQTT is also left unchanged.
Enable **Automatically re-enable disabled tablet MQTT** to explicitly allow
recovery to change `persist.start.mqtt` from `0` to `1`. This option only
applies while automatic recovery is enabled.
It overrides intentional tablet MQTT disables, but does not change the MQTT
broker address, port, credentials, or client ID. Successful re-enabling is
logged separately and verified by checking both the enable property and the
running service; sensor readings still require actual MQTT publications.
Re-enabling uses the same five-minute cooldown as restarting.
Turn this option off before intentionally disabling tablet MQTT.

The manifest defaults both automatic recovery and automatic re-enabling to
`true`. The configuration parser falls back to `false` if a setting is absent
or invalid; existing saved settings also affect the effective configuration.
Check the plugin settings rather than assuming recovery is disabled by default.

Restart attempts
and failed checks are limited to once every five minutes. Rejected service
bindings, unexpected service states, unsupported firmware, timeouts, and
transaction failures are reported in the plugin status and log. Disabling
recovery or the plugin stops new checks; an already submitted operation may
finish. The plugin does not stop the built-in MQTT client when disabled.

The Binder descriptor and transaction number are verified for the two exact
firmware builds listed under **Known devices**. Other builds remain blocked;
the error includes the detected build, and normal MQTT capture remains
available with automatic recovery disabled.
Recovery sends only the fixed `persist.start.mqtt=1` operation;
it does not expose arbitrary OEM commands or property writes. SDK 1 has no
Android Context accessor, so recovery obtains the host application using
`ActivityThread.currentApplication`, verified on this firmware with target
SDK 36. If a future host or firmware blocks that lookup, recovery reports
the failure rather than silently claiming success.

## Embedded MQTT sensor bridge

Point the tablet's OEM `iot_mqtt_client` at `127.0.0.1` and port `1883`.
The broker port is fixed and is not exposed as a plugin setting.
The broker binds exclusively to IPv4 loopback, so connections from the
tablet's LAN address or another device are not accepted. It supports MQTT
3.1, 3.1.1, and 5 and the protocol operations needed to capture
publications. It is not a general-purpose persistent broker.
It accepts multiple concurrent loopback clients, allowing OEM builds that
use separate MQTT connections for sensor publishing and command
subscriptions to operate correctly.

The broker recognizes these topic tokens:

- Temperature: `temperature`, `temp`, `ths`
- Humidity: `humidity`, `humid`, `hum`, `moisture`
- Distance: `distance`

Payloads may be plain numbers or JSON objects with matching keys. Known OEM
examples are:

```text
office/sensors/temperature {"temperature":26.4}
office/sensors/humidity {"humidity":38.5}
rk3576_u/sensors/distance {"distance":802}
office/switch1/status {"state":"OFF"}
office/switch2/status {"state":"OFF"}
office/door1/status {"state":"OFF"}
office/door2/status {"state":"OFF"}
```

Distance readings are exposed unchanged as millimetres (for example, `802`
means `802 mm`), based on the reported unit's scale. The MQTT payload does
not declare a unit. Units without a distance publication have no Distance
entity.
Negative or non-finite distance readings are rejected and logged.

The prefix is not fixed: any topic ending in `/switch1/status`,
`/switch2/status`, `/door1/status`, or `/door2/status` is recognized.
`ON` maps to active/open and `OFF` maps to inactive/closed.
Switch commands are published as plain `ON` or `OFF` payloads to the
corresponding `/switch1/set` and `/switch2/set` topic. Switch status
publications remain JSON, for example `{"state":"ON"}`. The switches appear
after their first status publication establishes a confirmed state and
topic prefix.

Switch 1 controls the tablet's **Relay 1** output; Switch 2 controls
**Relay 2**. What each relay operates depends on the wiring at your
installation; the plugin cannot identify the connected load. No particular
load, contact type, or electrical rating has been verified by this project.
Check your device's documentation and wiring before using these controls.
Home Assistant toggles send commands immediately, without a confirmation
prompt, and may operate connected equipment. Keep unsafe or unverified
loads disconnected while testing. Physical switch operation on the V2.0
firmware remains unverified as noted above.

The RGB backlight learns the topic prefix (for example `office`) from the
tablet's live status publications:

```text
office/light/status       {"state":"OFF"}
office/brightness/status  {"brightness":20}
office/rgb/status         {"rgb":[255,255,255]}
```

Power commands are sent to `light/switch` as plain `ON` or `OFF` payloads.
RGB commands are sent to `rgb/set` as plain comma-separated channels such
as `255,193,141`. Brightness commands use `brightness/set` with a plain
0–100 value. Status publications remain JSON.
In local mode, every successful OEM connection to the embedded MQTT broker
arms a check of the next valid RGB power status, including reconnects.
If it is `ON`, the plugin sends one `OFF` command on the command thread.
Brightness and color reports do not trigger this check. Later power changes
are left alone until the next MQTT connection. A settings change alone does
not arm the check; the ensuing MQTT connection does. A user RGB command
before the first power report cancels the current connection's check.
Units without RGB power status receive no automatic command. Delivery failures are reported
through the existing command status and log; the command is not retried
until a new connection and an `ON` power report.
`office/backlight/status` is the tablet display backlight, not the RGB
light, and is intentionally ignored.

### MQTT client ID and topic prefix

The `client_id` in the tablet's Android MQTT settings is also used by the OEM
client as the prefix for
its status and command topics. In the examples above the `client_id` is
`office`, but another tablet may use `kitchen` or `hall-panel`.

The plugin learns each command prefix from valid `/status` publications;
no manual override is available. Previously saved `mqttClientId` plugin
settings are ignored. For example, a tablet using
`kitchen` sends RGB commands to `kitchen/light/switch`,
`kitchen/brightness/set`, and `kitchen/rgb/set`, and switch commands to
`kitchen/switch1/set` and `kitchen/switch2/set`.

The plugin log records the topic filters requested by the built-in MQTT client
and manual outbound RGB or switch commands. Routine RGB effect traffic is
suppressed as described below. When a firmware publishes status
without subscribing to command topics, the loopback broker sends commands
only to connected clients that have published valid switch or RGB status
with the exact matching prefix, and logs that fallback for manual commands. It retains the latest
prefix for each of the five controllable status channels per connection,
not a history of publications. An explicit matching subscription takes
precedence. With neither a matching subscriber nor a known status publisher,
the command fails visibly instead of being broadcast. Ownership is forgotten
when a client disconnects or the broker stops; reconnecting clients must
publish status again or subscribe before they can receive commands.

The broker accepts any MQTT client credentials, but its loopback-only bind
keeps it inaccessible to other devices.

### RGB effects

The advertised effect list contains Rainbow, Breathe, Candle, Warning Red,
Warning Amber, Warning Yellow, Warning Green, Warning Cyan, Warning Blue,
Warning Magenta, and Warning White. `None` stops the active effect.
Rainbow cycles hue over 12 seconds; Breathe varies the starting brightness
between 10% and 100% over four seconds while retaining its starting color.
Candle uses warm RGB with a varying brightness. Warning colors are fixed;
all effects use the brightness captured when they start.

RGB effects are scheduled with fixed delay on the single command executor:
250 ms for Rainbow/Breathe/Candle and 500 ms for warning effects. After completing a
phase's commands warning effects hold for at least half a second before starting the next
phase; phases advance sequentially rather than skipping based on wall time.
Each new warning task starts in the off phase, including reselecting an active
warning effect. Every flash is preceded by an explicit OFF command and hold.
This is fixed pacing, not acknowledgement-based flow control. Only one
effect task and one base state are retained; there is no frame queue/history.
Smooth effects retain at most three last-sent topic/payload pairs in the effect
task and skip identical integer payloads. The cache starts empty for each effect
and is cleared when a client connects, so initial commands are resent. Warning
frames bypass this filtering. Cancellation releases the task and its cache.
Routine effect command/delivery logs and incoming RGB status logs are suppressed
while an effect is active; lifecycle, connection, invalid status, and failure
logs remain enabled. The latest-payload diagnostic still receives every message.
Warning off frames send only `light/switch=OFF`, with no brightness or RGB
writes that could relight the LEDs. The next on frame restores the selected
color and brightness.
The selected effect is published once after its first successful frame, with
logical power on, white RGB, and 100% brightness, independently of the physical
base brightness/color. Subsequent animation
frames and OEM status echoes update physical state internally without publishing
entity changes. Manual commands or effect termination resume normal state reporting.
Cancellation generations reject obsolete commands between MQTT writes.
Manual light commands, configuration changes, and stop cancel the future;
executor shutdown releases the task on unload. Stopping leaves the last
physical state in place; an explicit command chooses a final power/color.
Effects are not persisted or automatically resumed.
No transition duration support is claimed.

### Capability discovery and startup timeout

Startup and configuration changes show
`Detecting device capabilities, please wait.` in the info bar.
An existing one-second diagnostic task checks for the first MQTT publication.
At 60 seconds without one, it reports an error asking whether the built-in MQTT
client settings have been configured, with a link to
[Tablet MQTT settings](../README.md#tablet-mqtt-settings).
An MQTT connection alone does not satisfy this check. Any publication does,
even if it does not contain a recognized capability.
The timeout is reported once per configuration, independently of automatic
recovery. Informational statuses, including recovery progress, cannot replace
the timeout error; other errors can. The first publication clears the timeout
flag and reports reception, and valid readings then update the capability list.
Reconfiguration resets the wait; stop disarms it.

Capability discovery posts an informational plugin status and log entry only
when a new supported feature sends a valid reading/status. A fixed set of
eight possible feature names is retained in discovery order (three numeric
sensors, two switches, two doors, and RGB light). Invalid/unknown messages
do not detect features, repeated readings do not repeat announcements, and
the set is cleared on settings changes and stop. Operational status messages
can subsequently replace the discovery message.

### Entity state refresh and diagnostics

The `last_mqtt_message` Remote Admin status tile reports the embedded broker's
current client count in its state line on connect/disconnect and the existing 60-second refresh.
It uses the broker's connection collection rather than maintaining another
client registry.
The count is not an ESPHome entity and does not confirm message delivery.

The Remote Admin overview includes the `last_mqtt_message` plain-text status
tile, published through `publishStatusTile`, not the entity API. It is never exposed through
ESPHome or Home Assistant.
The title is `MQTT Broker`. In local mode the state line is
`1 client connected: <id>` or `<count> clients connected: <id>, ...`, without
a timestamp. Client IDs come from MQTT CONNECT and are retained only on
active broker connections. The display is sanitized and capped at 80
characters; empty IDs appear as `(unnamed)`. Disconnects remove IDs from the
next snapshot, without a separate diagnostic registry or history.
The dot is red (`off`) for zero local clients and green (`on`) for one or more.
It indicates connections, not message delivery.
The host clears tiles on stop; the plugin resets the tile on reconfiguration.
The second tile, `last_mqtt_payload`, shows an 80-character preview of the
latest inbound payload, with ISO control characters replaced by spaces and
`...` indicating truncation. It retains only that bounded preview, never the
full payload or a message history. During traffic it publishes at most once
per clock second, with the latest pending preview also sent by the existing
60-second refresh. Both tiles reset together. A single one-second task on
the existing plugin executor flushes pending payload previews and checks
freshness and the startup timeout, publishing only when the preview or indicator changes. The task
is shut down with the executor on stop and does not publish after stop.
With automatic MQTT recovery enabled in local mode, the payload indicator
is green for an inbound publication less than 60
seconds old, red at 60 seconds or before the first publication, and neutral
when automatic recovery is disabled. The preview remains
visible with recovery disabled. Recovery/re-enabling behavior is unchanged.

All entities are detected from their own valid MQTT publications, rather than
created at startup. Temperature, humidity, distance, doors, switches, and RGB
backlight therefore appear only on units that publish their readings or status.
Any valid RGB power, brightness, or color status detects the RGB backlight.

Every 60 seconds the plugin re-sends the last received temperature,
humidity, distance, door, and switch states to Kiosk Satellite. A reading
dropped by the host therefore recovers without waiting for the OEM value to
change. The cache is reset and existing entities are removed when the
plugin starts or its settings change. Each entity reappears after its next
valid publication.

Temperature, humidity, and distance readings older than 30 minutes are not
re-sent; they are set to `Unknown` and the log records
`No sensor.<name> reading received for 30 minutes`. Door and switch states
may only be published by the OEM client when they change, so they do not
expire. If no MQTT publication of any kind arrives for 10 minutes, the plugin
logs and shows a status warning with the number of open OEM MQTT client
connections. It reports `MQTT publications resumed.` when publications
arrive again.

Every OEM MQTT client disconnect is logged with its client ID, reason (for
example `DISCONNECT received`, `connection closed by client`, or
`Read timed out`), and connection duration. If the broker stops listening,
for example because port 1883 is still held, it retries after 5, 10, and
then every 30 seconds, and shows a status until it recovers.

If Kiosk Satellite rejects an entity update, the plugin logs
`Kiosk rejected <entity> update ...` at most once per minute per entity and
writes the same line to logcat via `System.err`. When updates are accepted
again, it logs `Kiosk accepted <entity> updates again.` If the rejection
means Kiosk has ended the plugin session, the plugin logs
`Kiosk ended this plugin session` and stops its broker so the next session
can bind port 1883.

Switch commands, manual RGB commands, and effect frames share one scheduled
command worker. MQTT input is processed independently on one thread per
connected client, while configuration, periodic refresh, and diagnostics use
a separate plugin worker. Recovery has its own worker and timeout watchdog.
Shared state is coordinated with locks; host calls during message processing
can still delay reading subsequent packets.

Fixed-delay effect scheduling prevents frame backlog, but a stalled socket
write can delay manual commands on the shared command worker. The MQTT socket
read timeout does not bound writes. Moving writes off the host callback thread
avoids doing socket I/O directly in Kiosk's event callback; it does not guarantee
that all callbacks remain non-blocking or that inbound traffic cannot back up.

## Serial and IR observations

The following observations were made on 10 October 2026 against the
original V1.0A firmware listed under **Known devices**. They describe investigation
results, not implemented plugin features. The tablet has separate physical
RS232 and RS485 ports; this plugin currently exposes neither port nor IR.

### UART access and mapping

| Device node | Observation |
| --- | --- |
| `/dev/ttyS1` | Labelled `smt_serial_device`; candidate for one external serial connector |
| `/dev/ttyS9` | Labelled `smt_serial_device`; candidate for the other external serial connector |
| `/dev/ttyS6` | Explicitly selected by the OEM Zigbee startup configuration at 115200 baud; a responding physical radio has not been verified |

The mapping of `/dev/ttyS1` and `/dev/ttyS9` to RS232 versus RS485 is
**not confirmed**. Three internal UART nodes do not mean three external
serial connectors. Baud rates, connector pinouts, and RS485 transmit/receive
direction control also remain unverified.

A temporary diagnostic plugin tested read/write opens from inside Kiosk
Satellite, under its actual application identity. All three nodes returned
`EACCES (Permission denied)`, with explicit SELinux read/write denials.
No port opened, no serial data was read or transmitted, and no serial
configuration was changed.

The firmware includes `/system/xbin/lmiot-efr32`, whose binary contains
Silicon Labs Ember/Zigbee gateway functions. Both the OEM startup script
and the `zigbee_gw` init declaration select `/dev/ttyS6` at 115200 baud.
This confirms the software's intended Zigbee UART, not the presence or
health of a radio on this particular tablet.

At inspection, no Zigbee gateway or bridge process was running, and the
queried Zigbee init-service properties were absent. The init declaration
points to `/data/vensi/lmiot-efr32`, which was not accessible through ADB;
the older `zigbee` service declaration in the board init file is commented
out. The presence of scripts and binaries therefore does not establish
that the gateway is active or correctly installed.

Although the nodes have permissive filesystem permissions, the firmware's
SELinux policy restricts access. Its vendor policy explicitly allows
platform/system application domains to access `smt_serial_device`, but
Kiosk Satellite runs in the `untrusted_app` domain. Adding a native serial
library does not give the plugin the OEM apps' security identity.

### OEM apps and services inspected

| Area | Result |
| --- | --- |
| OEM Binder service | No dedicated serial open/read/write or file-descriptor handoff API was found in the inspected interface |
| OEM `executeCommand` hook | An identity-only call from Kiosk Satellite reached the OEM service, but failed because its implementation tries to launch `su`: `Cannot run program "su": No such file or directory` |
| KeepAlive, OEM kiosk, and Settings apps | No serial-forwarding API was found in the inspected implementations |
| Factory-test apps | Serial diagnostics exist, but no callable serial-serving service appeared in the inspected exported component listings; their APKs were not readable through ADB, so inspection is incomplete |
| Firmware Zigbee bridge | A Zigbee/MQTT stack exists, but this does not establish a general-purpose RS232/RS485 network bridge |
| Android serial service | Present; the tested query was rejected because it requires restricted `android.permission.SERIAL_PORT` access |
| Android IR support | No standard `consumer_ir` service or advertised consumer-IR feature was found; an OEM-specific IR interface has not been established |

Launching the generic factory serial test directly tried `/dev/ttySnull`
and failed, indicating missing port-selection input. That result is not
evidence of faulty serial hardware. The OEM service used for MQTT recovery
performs an operation on the plugin's behalf; it does not transfer its
permissions to Kiosk Satellite.

### Android serial API versions and permissions

The public
[`android.hardware.serial.SerialPort`](https://developer.android.com/reference/android/hardware/serial/SerialPort)
API, including `requestOpen()`, was added in **API level 37**. It is not
available on the confirmed tablet's **Android 14 / API level 34** firmware.
This newer public API is distinct from the older restricted Android serial
service already present on the tablet.

Direct inspection confirmed that this firmware declares
`android.permission.SERIAL_PORT` with protection level
`signature|privileged`. The permission exists, but it is not a normal
runtime permission that a user can grant to Kiosk Satellite through a
settings prompt.

The UART access probe used direct device opens rather than the Java serial
API and was still rejected by SELinux. Building a plugin against API 37
does not add that API to Android 14 or remove the device-access restrictions.

A manufacturer firmware upgrade supporting the newer public API could
provide another route. Its availability alone does not prove that the OEM
exposes these onboard RS232/RS485 ports through it: port enumeration,
authorization, and actual operation would need to be verified on the
upgraded firmware.

### Possible Home Assistant serial bridge

The intended direction is for Home Assistant to use equipment connected to
the **tablet's** RS232 or RS485 connector over the network:

```text
Home Assistant <-> tablet network serial endpoint <-> tablet RS232 / RS485 <-> equipment
```

Kiosk Satellite supports networking and native plugin libraries, but direct
UART access is blocked under the tested firmware policy. No usable existing
OEM serial-serving path has been verified.

A possible solution is a manufacturer-authorized companion service or a
firmware update exposing a narrowly scoped serial API. Manufacturer signing
or installation alone must not be assumed sufficient: the service also needs
the appropriate Android permissions and SELinux authorization. It could
perform UART operations on the plugin's behalf or host the network endpoint
itself.

After hardware access is established, choose raw TCP, RFC2217, or another
transport supported by the intended Home Assistant integration. RS232 and
RS485 should have independent configuration and exclusive client ownership.
Network serial endpoints must be restricted to a trusted LAN/VPN and
authorized clients, not exposed directly to the internet. IR would require
a separate verified interface; network serial support does not automatically
expose IR.

Temporary diagnostic plugins were removed after testing. No firmware
permissions were changed, and the existing MQTT sensor plugin was left
enabled.

## Build and test

Plugin and SDK sources target Java 8 bytecode for Android compatibility.
Tests target Java 17 to support JUnit 6 and run on the Java 17 toolchain;
test classes are not included in the plugin package.
This test-only target change accompanies the JUnit 6 dependency update.
The published v0.4.0 source still uses JUnit 5 and targets tests at Java 8;
the plugin/SDK Java 8 target is unchanged in both configurations.

Requirements:

- JDK 17 toolchain available (the build explicitly selects Java 17)
- Gradle 9 or newer
- Android SDK with an installed platform and build-tools containing `d8`
- `ANDROID_HOME` or `ANDROID_SDK_ROOT`

Run:

```bash
gradle clean build
```

The installable ZIP and SHA-256 checksum are generated in `dist/`.
The `androidPlatform` Gradle property selects a platform; without it the build
uses the highest installed platform. CI and the release script explicitly select API 35:

```bash
gradle --no-daemon clean build -PandroidPlatform=35
```

## Automated CI and releases

Pushes to `main`, pull requests, and manual build workflow runs execute the complete build
and test suite. Each run starts with `clean`, so its `smt101-plugin-dist`
GitHub Actions artifact contains only the current manifest, installable ZIP,
and SHA-256 checksum. CI artifacts are retained for 30 days.

Releases are fully automated with
[semantic-release](https://semantic-release.org). Every push to `main` runs
`.github/workflows/release.yml`, which reads the
[Conventional Commits](https://www.conventionalcommits.org) since the last
`v<version>` tag and decides the next version:

| Commit message                                   | Release         |
| ------------------------------------------------ | --------------- |
| `fix: ...`, `perf: ...`                          | patch (0.1.x)   |
| `feat: ...`                                      | minor (0.x.0)   |
| `feat!: ...` or a `BREAKING CHANGE:` footer      | major (x.0.0)   |
| `refactor:`, `chore:`, `ci:`, `docs:`, `test:`, other text | no release |

When a release is due, `scripts/prepare-release.sh` writes the new version
into `kiosk-satellite-plugin.json`, runs the same clean build and tests, and
verifies the ZIP and checksum. semantic-release then commits the manifest back
to `main` as `chore(release): <version> [skip ci]`, tags `v<version>`, and
creates the GitHub Release with generated notes. Older releases, their
assets, and version tags are retained for version-specific references and
rollback. Releases deleted by earlier versions of the workflow are not
automatically restored.

Each release uploads exactly one standalone `kiosk-satellite-plugin.json`
asset for Plugin Manager discovery, plus the installable ZIP and its
SHA-256 checksum.

Do not bump the manifest version by hand. When squash-merging pull requests,
use a Conventional Commit PR title (for example `fix: resend last MQTT values`),
because that title becomes the commit semantic-release analyzes.
Individual feature commits do not trigger a release if a squash merge replaces
them with a non-releasing subject such as `refactor:`.

Weekly Dependabot checks cover Gradle dependencies, npm release tooling, and
GitHub Actions through `.github/dependabot.yml`, using `chore(deps)` commit
prefixes. These dependency commits do not trigger releases under the current
semantic-release rules.
