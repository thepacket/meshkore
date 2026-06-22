# meshcore-android

> ⚠️ **Early development.** Scaffold stage — the protocol codec and BLE transport are
> in place and unit-tested at the codec level, but the app has **not yet been validated
> against physical hardware**.

An open-source (**MIT**) Android client for [MeshCore](https://github.com/meshcore-dev/MeshCore)
**companion** devices over **BLE** — an alternative to the official app, built natively in
**Kotlin + Jetpack Compose**.

This is a separate, parallel project to
[meshcore-standalone](https://github.com/thepacket/meshcore-standalone) (the on-device
T-Deck UI). That one runs *on* the radio; this one talks *to* it from a phone.

## Why it's a good fit

The companion firmware exposes a **Nordic UART Service** (NUS) and a compact
length-delimited frame protocol (`CMD_*` requests, `RESP_*`/`PUSH_*` replies, all
little-endian). The protocol is the hard part, and it's fully captured in a pure-Kotlin,
device-free, unit-tested module.

## Module layout

```
core-protocol   Pure Kotlin/JVM. Frame codec + models. No Android deps → JVM unit tests.
core-ble        Android library. NUS transport (Nordic BLE) behind a MeshCoreLink interface.
app             Jetpack Compose UI + view-models.
```

The UI never touches BLE/Nordic types directly — it depends only on `MeshCoreLink`
(connect / send / `Flow<Incoming>`), so the transport can later grow USB/Wi-Fi without
touching feature code.

## Protocol summary

- **Service:** `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` · **RX (write):** `…0002` · **TX (notify):** `…0003`
- One frame per BLE write/notify, no length prefix; first byte = command/response code.
- All multi-byte integers little-endian; strings are fixed-width NUL-terminated or trailing.
- See [`core-protocol`](core-protocol/src/main/kotlin/org/thepacket/meshcore/protocol)
  for the full `Cmd` / `Resp` / `Push` code tables and the `FrameDecoder`.

Some frame payload layouts are confirmed against the firmware (contacts, sent/confirm,
adverts); others are marked `TODO: confirm` in the codec and must be byte-verified on a
real device before they're trusted.

## Roadmap

- **P0 — Connect + config** *(scaffolded)* — scan, connect, `APP_START` handshake → device info; then full settings read/write.
- **P1 — Messaging** — contacts, DMs, channels, delivery status.
- **P2 — Instrumentation** — packet analysis, background-noise graph, last-heard/SNR.
- **P3 — Repeater management** — remote login, stats, scanner/whitelist, triggers.
- **P4 — Map / GPS** — node plotting from advert lat/lon, location share.

## Building

Requires Android Studio (Koala or newer) or a local Gradle + Android SDK.

```sh
# The Gradle wrapper jar is generated on first open in Android Studio,
# or run once with a system Gradle:
gradle wrapper --gradle-version 8.10.2

./gradlew :core-protocol:test     # run the codec unit tests (no device needed)
./gradlew :app:assembleDebug      # build the APK
```

Minimum Android 8.0 (API 26); compiled/targeted at API 35. Handles both the modern
(`BLUETOOTH_SCAN`/`CONNECT`, API 31+) and legacy (location-based scan, API 26–30)
permission models.

## License

MIT — see [LICENSE](LICENSE). Third-party deps in [THIRD_PARTY.md](THIRD_PARTY.md).
Not affiliated with or endorsed by the MeshCore project; all credit for the mesh stack,
firmware, and protocol belongs to MeshCore.
