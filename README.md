# SMT101x Sensors for Kiosk Satellite

This SDK 1 Kiosk Satellite plugin exposes the tablet's temperature,
humidity, distance (on equipped units), switch/door inputs, and RGB backlight
as Kiosk Satellite entities.

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

On the V2.0 tablet, the patched plugin restarted an enabled but stopped
publisher through the OEM service on 17 October 2026. Temperature, humidity,
and distance readings resumed. When testing a local ZIP on a fleet-managed
tablet, disable plugin synchronization first; otherwise the leader can
replace the local build with its configured release.

Other SMT101-family devices or firmware versions may use different MQTT
topics, payloads, or hardware interfaces and are not currently confirmed.

## Sensor sources

| Entity | Source | Metadata |
| --- | --- | --- |
| Temperature | OEM MQTT publication | `°C`, temperature, measurement, 1 decimal |
| Humidity | OEM MQTT publication | `%`, humidity, measurement, 0 decimals |
| Distance | OEM MQTT publication (on equipped units) | `mm`, distance, measurement, 0 decimals |
| Switch 1 / Switch 2 | OEM MQTT status and command topics | Writable switches |
| Door 1 / Door 2 | OEM MQTT status publications | Binary sensors with the `door` device class |
| RGB Backlight | OEM MQTT command and state topics | On/off, brightness, and RGB color |

The plugin does not inspect `/dev/input`.
Optional automatic recovery uses the tablet's exported
`com.broadcastinterface.ApiSystemService` to start a stopped OEM publisher.

## Configuration

| Setting | Type | Default | Notes |
| --- | --- | --- | --- |
| `enableEmbeddedMqttBroker` | boolean | `true` | Hosts the MQTT capture broker used to receive OEM sensor publications. |
| `mqttClientId` | string | blank | Must match `client_id` in the tablet's MQTT settings, such as `office`. Blank enables automatic discovery. |
| `enableAutomaticMqttRecovery` | boolean | `false` | Opt-in recovery of the stopped OEM MQTT publisher through the OEM system service. |
| `reenableDisabledMqtt` | boolean | `false` | Allows enabled recovery to override the tablet's MQTT disable setting in either mode. |

## Operating modes

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

### Local sensor management through Kiosk Satellite

Turn **Host MQTT capture broker on this tablet** on and configure the tablet's
MQTT server as `127.0.0.1`, port `1883`. The plugin receives sensor and door
readings and exposes switch and RGB controls through Kiosk Satellite.
No separate MQTT server is required: MQTT still runs internally between
the OEM publisher and the plugin's built-in loopback broker.
Enable automatic recovery to restart a stopped publisher in this mode too.

#### Tablet MQTT settings

Open **Android Settings > System > MQTT**. On the confirmed firmware,
tapping the MQTT row while enabled turns the publisher off; tapping it
again opens the **Set Mqtt** configuration dialog. This briefly interrupts
sensor publications. If automatic re-enabling is enabled, turn that plugin
option off temporarily so recovery does not interfere with configuration.

Use these values for the built-in local broker:

| Field | Value |
| --- | --- |
| host | `127.0.0.1` |
| port | `1883` |
| username | Leave blank |
| password | Leave blank |
| client_id | `office` in this example; keep your tablet's client ID |
| keepalive | `30` |

![Local MQTT configuration dialog captured from the SMT101x tablet](docs/images/local-mqtt-settings.png)

Tap **OK** to save and enable the OEM MQTT publisher. Ensure the MQTT switch
is on when finished. The switch indicates that MQTT is enabled, not that
sensor publications are reaching the plugin.

#### Plugin settings

| Setting | Local-mode value |
| --- | --- |
| **Host MQTT capture broker on this tablet** | **On** |
| `mqttClientId` | Leave blank for automatic discovery, or match the tablet's `client_id` |
| **Automatically recover stopped OEM MQTT publisher** | **On** for unattended recovery |
| **Automatically re-enable disabled tablet MQTT** | Optional: **On** if recovery should also turn the Android MQTT switch back on |

Restore automatic re-enabling after configuration if you want that behavior.
Leave it off to respect intentional MQTT disables. Confirm that real sensor
readings appear in Kiosk Satellite after saving the tablet settings.

Changing modes does not rewrite the tablet's MQTT connection settings.
Configure the correct server address in Android settings when switching.
Optional automatic re-enabling works in either mode and only changes the
MQTT enable switch, not the configured connection.

### Automatic OEM publisher recovery

The confirmed firmware declares `iot_mqtt_client` as a disabled, `oneshot`
Android init service. It does not automatically restart after a crash, even
when `persist.start.mqtt` remains `1`. This leaves sensors `Unknown` while
the plugin's broker is still listening.

To enable recovery, enable **Automatically recover stopped OEM MQTT
publisher** in the plugin page. Capture continues to work with recovery
disabled. After reboot, recovery resumes when Kiosk Satellite starts the
enabled plugin; ensure Kiosk Satellite is configured to launch on boot.

The plugin checks the service once per minute. Local mode waits until the
embedded broker is listening; external mode does not start a local broker.
On the confirmed firmware only, when tablet MQTT is enabled
(`persist.start.mqtt=1`) and the service is `stopped`, it reapplies
the MQTT start property through the OEM Binder service's
`setSystemProperties` operation. Android init starts the OEM service with its
normal identity; the plugin does not launch a daemon itself.
It verifies that the service is running after two seconds and logs that it
is waiting for publications in local mode. In external mode it reports only
that the publisher is running; external broker delivery is not monitored.
Actual sensor readings still come exclusively
from MQTT, not from the restart result.
Once a real MQTT publication arrives, the recovery status changes to
`MQTT publications are being received`. Publications arriving before the
restart confirmation are also recognized, so the confirmation cannot
overwrite active reception with a stale waiting message.

Running services and disabled tablet MQTT are left unchanged by default.
Enable **Automatically re-enable disabled tablet MQTT** to explicitly allow
recovery to change `persist.start.mqtt` from `0` to `1`. This option only
applies while automatic recovery is enabled, in either mode.
It overrides intentional tablet MQTT disables, but does not change the MQTT
broker address, port, credentials, or client ID. Successful re-enabling is
logged separately and verified by checking both the enable property and the
running service; sensor readings still require actual MQTT publications.
Re-enabling uses the same five-minute cooldown as restarting.
Turn this option off before intentionally disabling tablet MQTT.

Restart attempts
and failed checks are limited to once every five minutes. Rejected service
bindings, unexpected service states, unsupported firmware, timeouts, and
transaction failures are reported in the plugin status and log. Disabling
recovery or the plugin stops new checks; an already submitted operation may
finish. The plugin does not stop the OEM publisher when disabled.

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
Units without RGB power status receive no automatic command. External MQTT
recovery mode does not control the RGB light. Delivery failures are reported
through the existing command status and log; the command is not retried
until a new connection and an `ON` power report.
`office/backlight/status` is the tablet display backlight, not the RGB
light, and is intentionally ignored.

### MQTT client ID and topic prefix

The **MQTT client ID (optional)** setting corresponds to `client_id` in the
tablet's MQTT settings. The OEM client also uses that value as the prefix for
its status and command topics. In the examples above the `client_id` is
`office`, but another tablet may use `kitchen` or `hall-panel`.

Leave **MQTT client ID (optional)** blank to learn the prefix from the
tablet's `/status` publications. Set it explicitly to the same value as
`client_id` in the tablet's MQTT settings. For example, setting both to
`kitchen` sends RGB commands to `kitchen/light/switch`,
`kitchen/brightness/set`, and `kitchen/rgb/set`, and switch commands to
`kitchen/switch1/set` and `kitchen/switch2/set`.

Do not include a leading or trailing slash. The plugin removes those if
entered. MQTT wildcards (`+` and `#`) are not valid in this setting.

The plugin log records the topic filters requested by the OEM MQTT client
and every outbound RGB or switch command. When a firmware publishes status
without subscribing to command topics, the loopback broker also sends the
command directly to its connected OEM MQTT client and logs that fallback.

The broker accepts any MQTT client credentials, but its loopback-only bind
keeps it inaccessible to other devices.

### Entity state refresh and diagnostics

All entities are detected from their own valid MQTT publications, rather than
created at startup. Temperature, humidity, distance, doors, switches, and RGB
backlight therefore appear only on units that publish their readings or status.
Any valid RGB power, brightness, or color status detects the RGB backlight.
External MQTT recovery mode does not create local entities.

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

Switch and RGB backlight commands are sent from a dedicated plugin thread,
so a stalled MQTT write cannot trip Kiosk's plugin callback timeout.

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

Requirements:

- JDK 17 or newer
- Gradle 9 or newer
- Android SDK with an installed platform and build-tools containing `d8`
- `ANDROID_HOME` or `ANDROID_SDK_ROOT`

Run:

```bash
gradle clean build
```

The installable ZIP and SHA-256 checksum are generated in `dist/`.

## Automated CI and releases

Pushes, pull requests, and manual workflow runs execute the complete build
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
| `chore:`, `ci:`, `docs:`, `test:`, other text    | no release      |

When a release is due, `scripts/prepare-release.sh` writes the new version
into `kiosk-satellite-plugin.json`, runs the same clean build and tests, and
verifies the ZIP and checksum. semantic-release then commits the manifest back
to `main` as `chore(release): <version> [skip ci]`, tags `v<version>`, and
creates the GitHub Release with generated notes. After the new release
succeeds, older version releases and version tags are removed so only the
current package remains.

Each release uploads exactly one standalone `kiosk-satellite-plugin.json`
asset for Plugin Manager discovery, plus the installable ZIP and its
SHA-256 checksum.

Do not bump the manifest version by hand. When squash-merging pull requests,
use a Conventional Commit PR title (for example `fix: resend last MQTT values`),
because that title becomes the commit semantic-release analyzes.

## Installation

1. Build the plugin or download its ZIP.
2. Install the ZIP through Kiosk Satellite's Plugin Manager.
3. Configure the OEM MQTT client to publish to the tablet's embedded broker.
4. Confirm the plugin log reports an MQTT client connection and captured
   temperature/humidity publications.
