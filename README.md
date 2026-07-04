# MeshKore

An open-source (**MIT**) Android client for [MeshCore](https://github.com/meshcore-dev/MeshCore)
devices over **BLE** — an alternative to the official app, built natively in
**Kotlin + Jetpack Compose**.

## Features

Connect to a MeshCore device over BLE — including **PIN/passkey pairing** for
MITM-protected nodes (you're prompted for the device PIN on the first connection).
The app has seven tabs — **Chats · Heard · Packets · Stats · Map · Tools · Settings**:

- **Messaging (Chats)** — split into **Contacts** and **Channels** sub-tabs. Group
  **channels** and **direct messages** with speech-bubble threads, per-name colours,
  per-message **timestamps**, inbound SNR, and outbound **delivery status**
  (sending → sent → delivered ✓, via the ACK path); a failed send shows **tap-to-retry**.
  **Tap a message to reply** — the quote goes over the air as a leading `> …` line (the
  same convention as MeshKore's sibling on-device UI, so both render each other's
  replies), quoted lines display dimmed, and **http(s) links** in messages are tappable.
  Each conversation row carries an **unread badge**, and leaving a chat returns you to the
  sub-tab you came from. DM/channel conversations (and unread counts) are **persisted**
  locally across reconnects/restarts, and **background notifications** alert you to new
  messages while the app is closed (toggle per-type in Settings). The contact list has a
  **search** box.
  - **Contact management** — long-press a contact for **Share**, **Reset path**,
    **Export**, **Remove**, **Request telemetry** (the battery/sensor data the node
    chooses to share), **Show on map**, and **Get path to node** (multi-hop route
    discovery). **Import** a contact from an exported card, or via **QR** — share a
    contact as a scannable QR (the official `meshcore://` URL) and add one by scanning.
  - **Channel management** — **create**, **edit** (name + 128-bit key, with a randomize
    button) and **delete** channels, with guards against overwriting an occupied slot.
    The well-known **Public** channel (slot 0) is **protected** from edits/deletion and
    can be **restored** in one tap if it drifts. **Add region** pulls the community
    channel catalog (by country) and lets you tick which to add; or type a community
    **`#hashtag` channel** name and the key is derived automatically. A channel's key can
    be **shared as a QR** (base64, the on-device UI's format) and **scanned to join** —
    the key field accepts a scanned/pasted base64 or hex key.
  - **Room servers** — open a room contact to read and post to its **shared board**;
    **log in** (blank password for public rooms) so the room delivers posts, each shown
    with its **author**. (Room history lives on the server, not locally.)
  - **Repeater management** — tap a repeater to **log in** and view its **live status**
    (battery, uptime, packet counts, airtime, signal, dups, errors), **owner/firmware**,
    **neighbours** and **access list**, and run **admin / CLI commands** in a console.
  - **Remembered passwords** — a password a repeater/room accepts is stored and reused
    for **silent auto-login** the next time you open that node's management screen or
    room chat (with auto status fetch); a "Forget saved password" button clears it.
- **Heard** — recently-heard stations with a signal-graded dot, timestamp, SNR/RSSI, age,
  and distance (when both ends advertise GPS); tap for full details, **Show on map**, and
  **Get path to node**.
- **Packet monitor** — live decoded RX feed (source/destination, payload/route type,
  SNR, RSSI, length, receive time + elapsed/distance) with a **filter box** (by node,
  path, or type); tap for the full packet breakdown + raw hex, with **Show on map** and
  **Get path to node** when the source is known. The breakdown decodes as deep as the
  radio allows: **channel messages are decrypted** when the key is ours (MAC-checked,
  showing sender + text + sent time; "not our channel" otherwise), an **ACK is matched
  to our own outbound message** when its code corresponds, **trace packets** show their
  per-hop SNRs and target route, adverts show the **sender's clock skew** and position
  **distance**, and every packet gets a **link margin** (SNR above the SF's demod floor),
  an **airtime estimate**, and a **flood-rebroadcast count** (copies of the same payload
  in the log).
- **Statistics** — rolling **noise-floor graph**, radio (RSSI/SNR/airtime), device
  (battery/uptime/queue) and packet counters, plus **My Telemetry** (all of this node's
  telemetry channels), polled from the device.
- **Map** — OpenStreetMap view plotting nodes that advertise a position: round markers,
  towers for repeaters, colour-by-type, screen-space **clustering** with count pills,
  node labels, and a tap-for-details sheet (all fields copy-pasteable). A node's info
  panel can recentre the map on it via **Show on map**. The map is read-only and never
  reads the phone's GPS on its own — position is only read from the phone on an explicit
  tap in Settings.
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
  firmware's allowed repeat frequencies), advertised **position** (typed, picked on a map,
  or read from the **phone's GPS on an explicit tap**), network/telemetry options, tuning,
  auto-add, experimental **path-hash size**; plus **Device info**, config/app-data
  **export & import**, debug logs, reboot and factory reset.

Runtime permissions: Bluetooth (and, on Android ≤ 11, location for BLE scanning); plus
**location** — requested only when you tap "Use current location" to set this node's
position — and **camera**, only when scanning a contact QR.

> **Not yet implemented:** dedicated support for **Sensor** nodes (environmental
> readings / telemetry history) and a room **member-list** view. **Room servers**
> are supported — log in, then read and post to the shared board, with per-post
> authors. Note that a room server needs a correct clock (an RTC, or a clock set
> via its CLI) to relay posts between members.

## Status

| Area | State |
|---|---|
| Connect + device config (full editable settings) | ✅ done, hardware-validated |
| BLE PIN / passkey pairing (MITM companions) | ✅ done, hardware-validated |
| Messaging (DMs + channels + timestamps + delivery status + resend + persistence) | ✅ done, hardware-validated |
| Tap-to-reply quoting (`> …` interop with the on-device UI) + tappable links | 🆕 implemented, needs on-air validation |
| Unread badges (persisted) | ✅ done, hardware-validated |
| Background message notifications | ✅ done, hardware-validated |
| Contact management (search / share / reset-path / remove / export-import) | ✅ done, hardware-validated |
| QR contact share + scan (`meshcore://` URL) | ✅ done, hardware-validated |
| Channel management (create / edit / delete) | ✅ done, hardware-validated |
| Channel key QR (share as base64 QR / scan to join) | 🆕 implemented, needs on-air validation |
| Public channel protect + restore | ✅ done, hardware-validated |
| Region channels (community catalog) + `#hashtag` key derivation | ✅ done |
| Instrumentation (packet monitor / noise / stats / telemetry) | ✅ done, hardware-validated |
| Deep packet decode (channel decrypt / ACK match / trace SNRs / clock skew / link margin / airtime / rebroadcasts / filter) | 🆕 implemented, needs on-air validation |
| Remote telemetry (request a contact's telemetry) | ✅ done, hardware-validated |
| Path discovery ("Get path to node") | ⚠️ implemented; needs a responsive node/firmware to return a route |
| Last-heard | ✅ done, hardware-validated |
| Map (node positions + show-on-map) + GPS position (explicit) | ✅ done, hardware-validated |
| Tools — trace path (on map) + node discovery + advertise | ✅ done, hardware-validated |
| Repeater management (remote login / status / owner / neighbours / ACL / CLI) | ✅ done, hardware-validated |
| Room servers (log in, read + post the shared board, per-post authors) | ✅ done, hardware-validated |
| Remembered admin/room passwords + silent auto-login | 🆕 implemented, needs on-air validation |

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
