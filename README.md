# meshcore-android

> ⚠️ **Early development.** The core feature set is built and **validated on real
> hardware**, but this is young software — expect rough edges and breaking changes.

An open-source (**MIT**) Android client for [MeshCore](https://github.com/meshcore-dev/MeshCore)
**companion** devices over **BLE** — an alternative to the official app, built natively in
**Kotlin + Jetpack Compose**.

This is a separate, parallel project to
[meshcore-standalone](https://github.com/thepacket/meshcore-standalone) (the on-device
T-Deck UI). That one runs *on* the radio; this one talks *to* it from a phone. The two
keep their own, independent visual languages.

## Features

Connect to a companion device over BLE. The app has seven tabs —
**Chats · Heard · Packets · Stats · Map · Tools · Settings**:

- **Messaging (Chats)** — split into **Contacts** and **Channels** sub-tabs. Group
  **channels** and **direct messages** with speech-bubble threads, per-name colours,
  inbound SNR, and outbound **delivery status** (sending → sent → delivered ✓, via the
  ACK path). Conversations are **persisted** locally across reconnects/restarts.
  - **Contact management** — long-press a contact for **Share**, **Reset path**,
    **Export** and **Remove**; **import** a contact from an exported card.
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
  - **Discover** — find nearby (one-hop) **companions, repeaters, room servers and
    sensors** via a zero-hop advert; discovered nodes are added to contacts.
- **Settings** — full editable device config: node name, **region presets**, frequency,
  bandwidth, SF, coding rate, **TX power**, advertised **position (set from a map)**,
  network/telemetry options, tuning, auto-add, experimental **path-hash size**; plus
  **Device info**, config/app-data **export & import**, debug logs, reboot and factory
  reset.

The only runtime permissions requested are Bluetooth (and, on Android ≤ 11,
location, which the OS requires for BLE scanning).

## Status

| Area | State |
|---|---|
| Connect + device config (full editable settings) | ✅ done, hardware-validated |
| Messaging (DMs + channels + delivery status + persistence) | ✅ done, hardware-validated |
| Contact management (share / reset-path / remove / export-import) | ✅ done; export/import round-trip not yet hardware-verified |
| Channel management (create / edit / delete) | ✅ done, hardware-validated |
| Instrumentation (packet monitor / noise / stats / telemetry) | ✅ done, hardware-validated |
| Last-heard | ✅ done, hardware-validated |
| Map (node positions) | ✅ done, hardware-validated |
| Tools — trace path (on map) + discover | ✅ done, hardware-validated |
| Repeater management (remote login/stats/triggers) | ⏸️ deferred until a repeater is available to test |
| Remote telemetry (request a contact's telemetry) | 🔜 planned next |

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
