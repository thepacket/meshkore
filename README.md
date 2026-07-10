# MeshKore

An open-source (**MIT**) Android client for [MeshCore](https://github.com/meshcore-dev/MeshCore)
devices over **BLE** — an alternative to the official app, built natively in
**Kotlin + Jetpack Compose**.

## Features

Connect to a MeshCore device over BLE, including **PIN/passkey pairing** for
MITM-protected nodes. Seven tabs — **Chats · Heard · Packets · Stats · Map · Tools · Settings**.

**Chats**

- Three sub-tabs — **All contacts**, **Device contacts**, **Channels**; each contact list
  filters by type (**Clients · Repeaters · Room Servers · Sensors**) with live counts and a search box.
- Direct messages and channel threads: speech bubbles, per-name colours, per-message
  timestamps, inbound SNR, and **delivery status** (sending → sent → delivered ✓) with tap-to-retry.
- **Tap a message to reply** — the `> …` quote interops with MeshKore's on-device UI; tappable
  `http(s)` links; per-conversation **unread badges**.
- Conversations and unread counts **persist** across reconnects/restarts; **background
  notifications** for new messages (per-type toggle).
- **Aggregate address book** — All contacts is the deduped, persistent union of contacts from
  **every device you've connected to**; one tap **pushes the whole book** to the connected device,
  skipping duplicates.
- **Contact actions** (long-press) — share, reset path, export, remove, request telemetry
  (live, plus **min / max / avg** over a window), show on map, get path to node, and cached
  **advert path**; opening a contact re-reads its current record from the device. Import from
  card or **QR**, and share a contact as a QR.
- **Channels** — create / edit (name + 128-bit key) / delete with slot guards; protected **Public**
  channel with one-tap restore; add community channels by **region catalog** or **`#hashtag`** key
  derivation; share/scan a channel key as a base64 QR.
- **Room servers** — log in and read/post the shared board, with per-post authors.
- **Repeater management** — log in (with an active-session check so a session that survived a
  reconnect logs you straight back in); live status (battery/uptime/packets/airtime/signal/dups/errors);
  owner/firmware; neighbours; access list; admin/CLI console. Pre-login **owner/clock probes**
  query a repeater without authenticating.
- **Remembered passwords** — silent auto-login to repeaters/rooms you've authenticated to.

**Heard** — recently-heard stations with a signal-graded dot, SNR/RSSI, age, and distance; tap for
details, show on map, and get path to node.

**Packet monitor** — live decoded RX feed with a filter box; deep decode: channel **decrypt**,
**ACK match** to your own sends, trace per-hop SNRs, advert **clock skew** + distance, **link margin**,
airtime estimate, and flood-rebroadcast count.

**Statistics** — rolling noise-floor graph; radio / device / packet counters; a **Contacts & channels**
card (address-book size, device contacts and channels vs. capacity); and **My Telemetry**.

**Map** — OpenStreetMap plot of positioned nodes drawn from the full address book: typed markers,
screen-space clustering, labels, and a tap-for-details sheet. Read-only — never reads the phone's GPS
on its own.

**Tools**

- **Trace path** — build a route by tapping repeaters on the map; see per-hop receive SNR.
- **Discover nodes** — one-hop discovery listing nearby companions/repeaters/rooms/sensors.
- **Advertise** — zero-hop, flood-routed, or copy this node's advert card to the clipboard.
- **Raw data** — send a raw custom-payload packet (text/hex) to a contact, and view received raw frames.

**Settings** — full editable device config: node name, region presets, frequency / bandwidth / SF /
coding rate, TX power, client-repeat (with allowed-frequency guard), advertised position (typed, picked
on a map, or phone GPS on an explicit tap), network & telemetry, tuning, auto-add, path-hash size,
**device variables** (firmware custom vars / sensor settings), **pairing PIN**, and a live **device
clock** (drift vs. the phone + one-tap sync); plus **identity backup** (export / import the node's
private key), device info, config/app-data export & import, debug logs, reboot, and factory reset.

Runtime permissions: Bluetooth (and, on Android ≤ 11, location for BLE scanning); **location** — only
when you tap "Use current location" to set this node's position; and **camera**, only when scanning a QR.

> **Not yet implemented:** a dedicated **Sensor** dashboard with **time-series history** (live
> telemetry and min/max/avg are available per contact) and a room **member-list** view. Note that
> a room server needs a correct clock (an RTC, or a clock set via its CLI) to relay posts between members.

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
