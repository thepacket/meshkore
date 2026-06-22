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

Connect to a companion device over BLE and:

- **Device config** — scan, connect, `APP_START` handshake → name, public key, frequency,
  bandwidth, SF, coding rate, TX power.
- **Messaging** — contacts sync, group **channels** (enumerated from the device) and
  **direct messages**; speech-bubble threads, per-name colours, inbound SNR, and outbound
  **delivery status** (sending → sent → delivered ✓, via the ACK path).
- **Packet monitor** — live decoded RX feed (payload/route type, SNR, RSSI, length;
  tap for raw hex).
- **Statistics** — rolling **noise-floor graph**, radio (RSSI/SNR/airtime), device
  (battery/uptime/queue) and packet counters, polled from the device.
- **Heard** — recently-heard stations with a signal-graded dot, SNR/RSSI, age, and
  distance (when both ends advertise GPS).
- **Map** — OpenStreetMap view (read-only) plotting nodes that advertise a position:
  round markers, towers for repeaters, colour-by-type, screen-space **clustering** with
  count pills, node labels, and a tap-for-details sheet (all fields copy-pasteable).

The only runtime permissions requested are Bluetooth (and, on Android ≤ 11,
location, which the OS requires for BLE scanning).

## Status

| Area | State |
|---|---|
| Connect + device config | ✅ done, hardware-validated |
| Messaging (DMs + channels + delivery status) | ✅ done, hardware-validated |
| Instrumentation (packet monitor / noise / stats) | ✅ done, hardware-validated |
| Last-heard | ✅ done, hardware-validated |
| Map (read-only node positions) | ✅ done, hardware-validated |
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

## Protocol summary

- **Service:** `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` · **RX (write):** `…0002` · **TX (notify):** `…0003`
- One frame per BLE write/notify, no length prefix; first byte = command/response code
  (pushes have the high bit set).
- All multi-byte integers little-endian; strings are fixed-width NUL-terminated or trailing.
- See [`core-protocol`](core-protocol/src/main/kotlin/org/thepacket/meshcore/protocol)
  for the full `Cmd` / `Resp` / `Push` code tables and the `FrameDecoder`.

Most frame layouts have been byte-verified against a live device. A couple still used only
by the deferred repeater feature (`RepeaterStats`, trace-hop records) are marked
`TODO: confirm` in the codec and will be verified on-device before that feature ships.

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
