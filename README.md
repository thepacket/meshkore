# MeshKore

## This project is in development. Not usable at this time. ##

An open-source (**MIT**) Android client for [MeshCore](https://github.com/meshcore-dev/MeshCore)
devices over **BLE** — an alternative to the official app, built natively in
**Kotlin + Jetpack Compose**.

## Features

Connect to a MeshCore device over BLE, including **PIN/passkey pairing** for
MITM-protected nodes — or run BLE-free, **observing live packets and channel chat over
meshcore.ca MQTT**. Seven tabs — **Chats · Heard · Packets · Tools · Map · Stats · Settings**.

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
  **every device you've connected to** *and every node observed advertising* (companion + MQTT),
  each tagged with its region; one tap pushes the **home-region contacts of the selected types** to
  the connected device, skipping duplicates.
- **Contact actions** (long-press) — share, reset path, export, remove, request telemetry
  (live, plus **min / max / avg** over a window), show on map, get path to node, and cached
  **advert path**; opening a contact re-reads its current record from the device. Import from
  card or **QR**, and share a contact as a QR.
- **Channels** — the list follows the selected region. On your **Home** region these are the device's
  own slots: create / edit (name + 128-bit key) / delete with slot guards; protected **Public** channel
  with one-tap restore; add community channels by **region catalog** or **`#hashtag`** key derivation;
  share/scan a channel key as a base64 QR. On any **observed** region they are the keys you follow there —
  **read-only** (no radio of yours is in that region), with no slots and no eight-channel cap, so you can
  follow as many as you like purely to read. The two never mix: separate keys, separate chat history.
- **Room servers** — log in and read/post the shared board, with per-post authors.
- **Repeater management** — log in (with an active-session check so a session that survived a
  reconnect logs you straight back in); live status (battery/uptime/packets/airtime/signal/dups/errors);
  owner/firmware; neighbours; access list; admin/CLI console. Pre-login **owner/clock probes**
  query a repeater without authenticating.
- **Remembered passwords** — silent auto-login to repeaters/rooms you've authenticated to.

**Heard** — recently-heard stations with a signal-graded dot, SNR/RSSI, age, and distance; tap for
details, show on map, and get path to node. Fed by the device's own advert pushes **and by adverts
observed over MQTT**.

**Packet monitor** — live decoded RX feed with a **Filters card** (by packet type, node type, route,
and SNR / RSSI / length / hop-count ranges, or GPS-only) and **group-by-hash** collapsing of flood
rebroadcasts into one expandable row (earliest kept). Deep decode: channel **decrypt** (with any key you
hold — the device's slots or the ones you follow in the region shown), **ACK match** to your own sends,
trace per-hop SNRs, advert **clock skew** + distance, **link margin**, airtime estimate, and
flood-rebroadcast count. Details also name the packet's **region** and whether your own radio heard it or
it was observed over MQTT — which is also what tells you whose SNR/RSSI you're reading. Relative ages tick
live; names resolve from the aggregate address book. Packets arrive from the connected device and/or the
**MQTT** feed.

**Statistics** — a **Contacts & channels** card (address-book size, device contacts and channels vs.
capacity); a **Traffic** analysis card over a persisted, cross-session packet history (5m / 1h / all
windows): capture rate, estimated **channel-busy** (airtime ÷ window), flood/direct split, and duplicate
(flood-rebroadcast) share, with a one-tap **clear** to reset the history; an **MQTT** card (per-broker
packet counts, total, disconnections, broker failovers); device and radio counters; and a rolling
**noise-floor** graph. (The deeper packet-history analytics live under **Tools ▸ Analytics**.)

**Map** — OpenStreetMap plot of positioned nodes drawn from the full address book: typed markers,
screen-space clustering, labels, and a tap-for-details sheet. Read-only — never reads the phone's GPS
on its own.

**Tools** — companion actions plus an **Analytics** section that mines the persisted RX history.

Companion tools (**disabled and greyed** when no BLE device is connected):

- **Trace path** — build a route by tapping repeaters on the map; see per-hop receive SNR.
- **Discover nodes** — one-hop discovery listing nearby companions/repeaters/rooms/sensors.
- **Advertise** — zero-hop, flood-routed, or copy this node's advert card to the clipboard.
- **Raw data** — send a raw custom-payload packet (text/hex) to a contact, and view received raw frames.

Analytics (work from the packet history, with or without a device):

- **Mesh topology** — an interactive routing graph centred on this node, from learned contact paths +
  observed packet relays. Pinch-zoom / pan, tap to focus a node and its neighbours, **export as SVG**.
  A link is a *known route* (not a live radio link).
- **Top Talkers** / **Top Repeaters** / **Repeater Pairs** — busiest sources, busiest relays, and
  repeater pairs that co-occur in paths (tap a pair member for its contact card + show-on-map).
- **Top Senders** — channel messages ranked by sender; **Message Types** — packets by payload type.
- **Hash Size** — path-hash sizes (1/2/3-byte routing IDs) across packets and by unique repeater.
- **Distributions** — **SNR**, **RSSI**, **hop count**, **hop distance** (km between consecutive path
  hops), and **packet size** histograms; **Signal Quality** (avg-SNR trend + volume over time); and an
  **SNR vs RSSI** scatter over link-quality zones.

**Region-aware resolution** — the 1-byte routing hashes in packet paths collide across the multi-region
meshcore.ca feed, so each packet is tagged with its region (from the MQTT topic) and hops are resolved
per region using adverts' full public keys. This keeps Top Talkers / Top Repeaters / Repeater Pairs /
topology **and the packet monitor** from merging distinct nodes that share a hash — across every analytics
tool. **Contacts are regional** — every observed advert (companion or MQTT) becomes a contact tagged with
the region it was heard in (survives restarts), and the **All contacts** list is scoped to the selected
region. The aggregate book collects *every* observed node, but "Send to device" only pushes contacts in
the **Home region** whose **type you selected** (Settings → *Contacts pushed to device*; chat by default).

Two region settings, doing different jobs:

- **Region** — what you're *looking at*. Drives the MQTT subscription topic, and selects which per-region
  packet feed, contact list, channel list, and analytics you see.
- **Home region** — where your *companion's radio* is. Packets it hears carry no region of their own, so
  Home is what attributes them to a place; without it they'd be filed under someone else's city. It is
  resolved when data is read, never baked into storage, so setting or changing Home re-attributes
  everything already collected — no history is lost or stranded. Unset until you choose one.

Because attribution is per-packet rather than per-mode, a BLE companion and the MQTT feed can run in the
**same session** without contaminating each other: your radio's traffic lands in Home, observed traffic
lands in its own region, whatever you happen to be viewing.

**Settings** — top of the list is the **Region** card (the region you're viewing, plus the **Home region**
where your companion's radio is) and **Global Contacts** / **Device contacts** cards (each with **export
all / import all / delete all**; global contacts also work with no device). Then full editable device config: node name, radio region presets, frequency / bandwidth / SF /
coding rate, TX power, client-repeat (with allowed-frequency guard), advertised position (typed, picked
on a map, or phone GPS on an explicit tap), **which contact types are pushed to the device**, network &
telemetry, tuning, auto-add, path-hash size, **device variables** (firmware custom vars / sensor
settings), **pairing PIN**, and a live **device clock** (drift vs. the phone + one-tap sync); plus
**identity backup** (export / import the node's private key), device info, config/app-data export &
import, debug logs, reboot, and factory reset. Also here: **live packets over MQTT** — optionally
subscribe to the **meshcore.ca** feed (broker + token auth; region from the global selector) to inject
observed packets into the packet monitor, Heard list, traffic stats, topology, and — for channels you
follow there — **chat**, with or without a BLE connection. On connection loss the feed **fails over to
the other broker** automatically.

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
