# meshcore-android

> ⚠️ **Early development.** The core feature set is built and **validated on real
> hardware**, but this is young software — expect rough edges and breaking changes.

An open-source (**MIT**) Android client for [MeshCore](https://github.com/meshcore-dev/MeshCore)
devices over **BLE** — an alternative to the official app, built natively in
**Kotlin + Jetpack Compose**.

## Features

Connect to a MeshCore device over BLE — including **PIN/passkey pairing** for
MITM-protected nodes (you're prompted for the device PIN on the first connection).
The app has seven tabs — **Chats · Heard · Packets · Stats · Map · Tools · Settings**:

- **Messaging (Chats)** — split into **Contacts** and **Channels** sub-tabs. Group
  **channels** and **direct messages** with speech-bubble threads, per-name colours,
  inbound SNR, and outbound **delivery status** (sending → sent → delivered ✓, via the
  ACK path). Conversations are **persisted** locally across reconnects/restarts.
  - **Contact management** — long-press a contact for **Share**, **Reset path**,
    **Export**, **Remove**, and **Request telemetry** (the battery/sensor data the
    node chooses to share); **import** a contact from an exported card.
  - **Channel management** — **create**, **edit** (name + 128-bit key, with a randomize
    button) and **delete** channels, with guards that prevent silently overwriting an
    existing slot (e.g. the Public channel).
- **Heard** — recently-heard stations with a signal-graded dot, SNR/RSSI, age, and
  distance (when both ends advertise GPS); tap for full details.
- **Packet monitor** — live decoded RX feed (source/destination, payload/route type,
  SNR, RSSI, length; tap for full packet breakdown + raw hex).
- **Statistics** — rolling **noise-floor graph**, radio (RSSI/SNR/airtime), device
  (battery/uptime/queue) and packet counters, plus **My Telemetry** (all of this node's
  telemetry channels), polled from the device.
- **Map** — OpenStreetMap view plotting nodes that advertise a position: round markers,
  towers for repeaters, colour-by-type, screen-space **clustering** with count pills,
  node labels, and a tap-for-details sheet (all fields copy-pasteable). The app never
  reads the phone's GPS.
- **Tools**
  - **Trace path** — build a route by tapping repeaters on the live map (a node may
    repeat); long-press a node for its details. Sends the trace and shows the
    **per-hop receive SNR** on return.
  - **Discover nodes** — send a one-hop discovery request and list the nearby nodes
    that answer (**companions, repeaters, room servers and sensors**), each tagged with
    its type and signal, sorted by signal strength. New nodes are added to contacts.
    Discover and Clear are user-driven, with an optional 60-second auto-refresh.
  - **Advertise** — announce this node **zero-hop** (direct neighbours) or **flood-routed**
    (whole mesh), or copy its advert **card to the clipboard** for sharing.
- **Settings** — full editable device config: node name, **region presets**, frequency,
  bandwidth, SF, coding rate, **TX power**, **client-repeat** (with a guard for the
  firmware's allowed repeat frequencies), advertised **position (set from a map)**,
  network/telemetry options, tuning, auto-add, experimental **path-hash size**; plus
  **Device info**, config/app-data **export & import**, debug logs, reboot and factory
  reset.

The only runtime permissions requested are Bluetooth (and, on Android ≤ 11,
location, which the OS requires for BLE scanning).

## Status

| Area | State |
|---|---|
| Connect + device config (full editable settings) | ✅ done, hardware-validated |
| BLE PIN / passkey pairing (MITM companions) | ✅ done, hardware-validated |
| Messaging (DMs + channels + delivery status + persistence) | ✅ done, hardware-validated |
| Contact management (share / reset-path / remove / export-import) | ✅ done, hardware-validated |
| Channel management (create / edit / delete) | ✅ done, hardware-validated |
| Instrumentation (packet monitor / noise / stats / telemetry) | ✅ done, hardware-validated |
| Remote telemetry (request a contact's telemetry) | ✅ done, hardware-validated |
| Last-heard | ✅ done, hardware-validated |
| Map (node positions) | ✅ done, hardware-validated |
| Tools — trace path (on map) + node discovery + advertise | ✅ done, hardware-validated |
| Repeater management (remote login/stats/triggers) | ⏸️ deferred until a repeater is available to test |

## Module layout

```
core-protocol   Pure Kotlin/JVM. Frame codec + models. No Android deps → JVM unit tests.
core-ble        Android library. NUS transport (Nordic BLE) behind a MeshCoreLink interface.
app             Jetpack Compose UI + view-models.
```

The UI never touches BLE/Nordic types directly — it depends only on `MeshCoreLink`
(connect / send / `Flow<Incoming>`), so the transport can later grow USB/Wi-Fi without
touching feature code. Post-connection orchestration (handshake, contacts/channel sync,
the message drain-loop, sends, stats polling, heard tracking) lives in `MeshSession`.

## Building

Requires Android Studio (Koala or newer), or a JDK 17+ with the Android SDK. The Gradle
wrapper is committed, so no separate Gradle install is needed.

```sh
./gradlew :core-protocol:test     # run the codec unit tests (no device needed)
./gradlew :app:assembleDebug      # build the debug APK
./gradlew :app:installDebug       # install on a connected device (real device — BLE needs hardware)
```

Minimum Android 8.0 (API 26); compiled/targeted at API 35. Handles both the modern
(`BLUETOOTH_SCAN`/`CONNECT`, API 31+) and legacy (location-based scan, API 26–30)
permission models.

## Contributing

This is an independent personal project and **does not accept pull requests** — any PR
is closed automatically. Fork freely (MIT permits it); see [CONTRIBUTING.md](CONTRIBUTING.md).
Bug reports and ideas are welcome via Issues.

## License

MIT — see [LICENSE](LICENSE). Third-party deps in [THIRD_PARTY.md](THIRD_PARTY.md).
Not affiliated with or endorsed by the MeshCore project; all credit for the mesh stack,
firmware, and protocol belongs to MeshCore.
