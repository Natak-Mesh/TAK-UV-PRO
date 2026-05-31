# agent meshcore handoff_v54

## Scope and intent

This document is the operational handoff for MeshCore-related work completed through `v54` in the `Darksteal` ATAK plugin, with enough depth for a new agent to continue debugging and implementation without replaying prior chats.

Primary objectives covered in this workstream:

- Integrate MeshCore transport and UX into `Darksteal` while preserving existing UV-PRO/APRS behavior.
- Support repeater/node discovery and map rendering from MeshCore companion packets.
- Support MeshCore channel chat with native-like status handling.
- Provide mesh contact details UX on map select (single-page panel with action buttons).
- Route map-selected mesh DM sends correctly (UID/pubkey-sensitive handling).
- Reduce transport instability impact (UV-PRO reconnect suppression when mesh is connected).

---

## Repositories and reference paths

### Main working plugin (this handoff target)

- `/home/paul/Documents/ATAK/Plugins/Darksteal`

### Standalone MeshCore ATAK plugin (reference implementation/parity target)

- `/home/paul/Documents/ATAK/Plugins/MeshcoreAtak`

### MeshCore upstream firmware/docs reference

- `/home/paul/Documents/ATAK/MeshCore-upstream`

### Prior handoff docs already in repo

- `/home/paul/Documents/ATAK/Plugins/Darksteal/docs/UV-PRO-v1.9.51-AGENT-HANDOFF.md`
- `/home/paul/Documents/ATAK/Plugins/Darksteal/docs/UV-PRO-INTEGRATION-TEST-PLAN.md`

---

## Current branch/commit status at handoff

- Branch: `main`
- Latest pushed commit at this handoff point: `2090c2e`
- Commit subject: `Stabilize MeshCore contact routing and advert handling for v54.`

Note: additional uncommitted local edits may exist after this commit depending on when this file is read; run `git status` first.

---

## High-level architecture: how MeshCore is handled in Darksteal

### 1) Dual transport model

Darksteal currently runs two radio transports:

- `BtConnectionManager` (classic BT SPP/KISS path, UV-PRO-centric)
- `MeshBtConnectionManager` (BLE companion protocol path for MeshCore devices)

Both feed into the same higher-level routing/injection layers (`PacketRouter`, `ChatBridge`, `CotBridge`), but packet formats and control channels differ significantly.

### 2) Mesh BLE companion ingest

Core file:

- `app/src/main/java/com/uvpro/plugin/bluetooth/MeshBtConnectionManager.java`

Key packet handling entry:

- `handleCompanionPacket(byte[] pkt)`

Important companion response/push codes used in this project:

- `0x80` (`PUSH_CODE_ADVERT`) - advert refresh/push (often pubkey-only)
- `0x8A` (`PUSH_CODE_NEW_ADVERT`) - new advert push
- `0x03` (`RESP_CODE_CONTACT`) - full contact payload for advert
- `0x88` (`PUSH_CODE_LOG_RX_DATA`) - RX log metadata/events
- `0x1B` (`RESP_CHANNEL_DATA_RECV`) - channel data receive

Mesh advert parse flow implemented:

1. Receive advert/contact frame.
2. Parse into `MeshAdvert` (`parseMeshAdvert`).
3. Dispatch to generic `meshAdvertListeners`.
4. If advert type is repeater, convert to `RepeaterAdvert` and notify repeater listeners.

### 3) Map rendering path

Core file:

- `app/src/main/java/com/uvpro/plugin/UVProMapComponent.java`

Listeners:

- `repeaterAdvertListener`
- `meshAdvertListener`

Rendering flow:

1. Build `mapUid` (`MESHCORE-RPTR-*` or `MESHCORE-NODE-*`).
2. Build details text (name, pubkey, coords, distance, type, last advert time).
3. Inject CoT marker via `CotBridge.injectPositionCotAtMapUid(...)`.
4. Mark item metadata (`markMeshRepeaterMapItem` / `markMeshNodeMapItem`).
5. Save rich details in marker metadata for details panel.

Visibility toggles are preference-driven:

- `PREF_MESH_SHOW_REPEATERS`
- `PREF_MESH_SHOW_NODES`

### 4) Mesh contact details UX

Core files:

- `app/src/main/java/com/uvpro/plugin/mesh/MeshDetailsDropDownReceiver.java`
- `app/src/main/res/layout/mesh_details_dropdown.xml`

Behavior:

- Mesh marker click opens single-page details panel.
- Includes buttons:
  - `Favorite` -> explicit contact creation in ATAK contact list.
  - `Send Message` -> open GeoChat for mesh routing.

### 5) Outbound mesh chat routing

Core file:

- `app/src/main/java/com/uvpro/plugin/chat/ChatBridge.java`

Relevant behavior added/refined:

- DM wire destination resolution for mesh paths now supports pubkey-style routing identifiers:
  - `MESHCORE-NODE-<64hex>`
  - `MESHCORE-RPTR-<64hex>`
  - raw 64-hex key hints
- Local mesh self pubkey support added to `MeshBtConnectionManager` and consumed by `ChatBridge`.

### 6) Contact/routing metadata bridge

Core file:

- `app/src/main/java/com/uvpro/plugin/cot/CotBridge.java`

Key role:

- Track plugin-managed UIDs and aliases (`registerBtechContactUid` / `registerBtechContactId`).
- Decide whether outbound GeoChat should relay over radio.
- Tag mesh map items with metadata for UI/routing behavior.

---

## Major changes completed in v54 workstream

## Mesh map + UX

- Added mesh section controls in dropdown:
  - Show Repeaters toggle (default ON)
  - Show Nodes toggle (default OFF)
  - Send Advert button
- Added Mesh details panel (single-page) for map-selected mesh contacts.
- Added `Favorite` and `Send Message` actions in details panel.
- Prevented passive advert reception from automatically creating ATAK contacts.

## Advert handling

- Generalized advert pipeline from repeater-only to generic mesh adverts:
  - `MeshAdvert` + `MeshAdvertListener`
  - repeater conversion path retained
- Added 0x80 advert-refresh follow-up behavior:
  - request full contact via `CMD_GET_CONTACT_BY_KEY`.
- Added node discovery toast dedupe.

## Mesh channel chat behavior

- Status behavior aligned with native expectations:
  - direct path fallback -> `Sent`
  - relayed path -> `heard X repeats`
- Improved channel log visual formatting and readability.

## Map-send DM routing refinements

- Forced exact UID targeting from mesh details panel send action.
- Added mesh pubkey candidate extraction in chat routing path.
- Added local mesh self-pubkey matching support for inbound destination checks.

## Transport stability controls

- Added reconnect blocker callback in `BtConnectionManager`.
- Wired blocker in `UVProMapComponent`:
  - suppress UV-PRO auto reconnect while mesh transport is currently connected.

## Mesh discovery tuning

- Device name match expanded for discovery robustness:
  - includes `lilygo` and `echo` matching.
- Mesh BLE scan timeout extended to 15 seconds.
- Bonded mesh candidates are emitted immediately at scan start.

---

## Current unresolved / active bugs

## 1) Repeater adverts intermittently not appearing in ATAK map (high priority)

Observed evidence:

- MeshCore app PID capture shows advert flow arriving correctly:
  - `responseCode=128 (0x80)` and `responseCode=3 (0x03 len=148)` seen.
- Simultaneous ATAK plugin captures often show:
  - no `0x80`, no `0x8A`, no `0x03` in `UVPro.MeshBLE` logs.
  - mostly `0x88`, `0x1B`, `0x0A`, `0x83`.

Implication:

- RF and mesh node/repeater side are functioning.
- Regression likely in ATAK companion queue/ingest timing or message-drain behavior.

Mitigations already applied:

- Immediate `getNextMessage` enqueue after handling `0x88`.
- Relaxed advert parse minimum length from `148` to `144`.

Still unresolved:

- Need proof that ATAK side receives `0x80/0x03` in failing scenario, or isolate why companion queue drains without surfacing these frames.

## 2) Map-selected mesh DM delivery reliability (medium/high)

Status:

- Multiple routing fixes implemented (exact UID targeting, pubkey-aware wire destination).
- Field verification is incomplete due issue switching and transport instability periods.

Needed:

- Controlled A/B validation with synchronized sender/receiver captures after stable transport.

## 3) Discovery edge-cases with certain boards (medium)

LILYGO/T-Echo name matching and scan hardening were added, but no definitive end-to-end confirmation that all affected devices now appear reliably under all BT advertising states.

---

## Technical debugging findings that matter

1. Transport instability can fake higher-level regressions:

- Earlier failures coincided with repeated UV-PRO reconnect loops (`SPP UUID failed`, `RFCOMM ch1 failed`, `Insecure SPP failed`).
- These masked advert/chat path signals and caused false negatives.

2. MeshCore app process logs are valuable truth source:

- PID-scoped logcat for `com.liamcottle.meshcore.android` confirmed advert frames in real time.
- This is a strong differentiator between RF-layer failures vs ATAK-plugin ingest failures.

3. Companion packet sequences can be firmware-timing dependent:

- In some runs, `0x88` and channel data activity appears without adjacent visible advert pushes on ATAK side.
- Queue-drain scheduling is likely sensitive.

---

## Files touched in this v54 cycle (core set)

- `app/src/main/java/com/uvpro/plugin/bluetooth/MeshBtConnectionManager.java`
- `app/src/main/java/com/uvpro/plugin/bluetooth/BtConnectionManager.java`
- `app/src/main/java/com/uvpro/plugin/UVProMapComponent.java`
- `app/src/main/java/com/uvpro/plugin/UVProDropDownReceiver.java`
- `app/src/main/java/com/uvpro/plugin/chat/ChatBridge.java`
- `app/src/main/java/com/uvpro/plugin/cot/CotBridge.java`
- `app/src/main/java/com/uvpro/plugin/cot/CotBuilder.java`
- `app/src/main/java/com/uvpro/plugin/UVProContactHandler.java`
- `app/src/main/java/com/uvpro/plugin/mesh/MeshDetailsDropDownReceiver.java`
- `app/src/main/java/com/uvpro/plugin/contacts/MeshFavoriteConnector.java`
- `app/src/main/java/com/uvpro/plugin/contacts/MeshSendMessageConnector.java`
- `app/src/main/res/layout/mesh_details_dropdown.xml`
- `app/src/main/res/layout/uvpro_dropdown.xml`

---

## Commands and procedures used repeatedly

## Build/compile

- Compile Java only:
  - `./gradlew :app:compileCivDebugJavaWithJavac`
- Full APK assemble:
  - `./gradlew :app:assembleCivDebug`

## Dynamic APK path resolution and deploy to all connected devices

Used pattern:

- Parse `app/build/outputs/apk/civ/debug/output-metadata.json`
- Install with `adb -s <serial> install -r <apk>`

## Device/process targeting

- List devices:
  - `adb devices -l`
- Check package presence:
  - `adb -s <serial> shell pm list packages`
- Get MeshCore app PID:
  - `adb -s <serial> shell pidof com.liamcottle.meshcore.android`

## Log capture patterns

- Full capture:
  - `adb -s <serial> logcat -c`
  - `adb -s <serial> logcat -v threadtime > /tmp/<name>.log`
- PID-scoped MeshCore capture:
  - `adb -s <serial> logcat --pid=<pid> -v threadtime > /tmp/<name>.log`

## Git operations used in v54 cycle

- status:
  - `git status --short --branch`
- inspect:
  - `git diff --stat`
  - `git log --oneline`
- commit:
  - `git add .`
  - `git commit -m "<message>"`
- push:
  - `git push origin main`

---

## Suggested next-agent execution plan

1. Validate current build state:

- `git status`
- `./gradlew :app:compileCivDebugJavaWithJavac`

2. Reproduce repeater advert failure with synchronized captures:

- ATAK plugin full log on receiving phone.
- MeshCore app PID log on same phone.
- Trigger one advert at known timestamp.

3. Compare sequence around advert time:

- MeshCore app: confirm `0x80 -> 0x03`.
- ATAK plugin: check for missing/late companion frame ingestion.

4. If ATAK still misses `0x80/0x03`:

- Instrument `MeshBtConnectionManager` with temporary high-granularity queue/dequeue logging around `PUSH_MESSAGES_WAITING`, `buildGetNextMessageCommand`, and parser dispatch branches.
- Ensure no early return path starves advert branches under heavy `0x88/0x1B` churn.

5. Once fixed:

- Re-test with repeaters ON, nodes OFF/ON permutations.
- Confirm map marker injection, details metadata, and toast behavior.
- Run one sanity DM send from map-selected mesh contact.

---

## Notes on this file and gitignore behavior

This handoff file is intentionally included in repo for agent continuity, while a general pattern is ignored for future local handoff variants.

Rules in `.gitignore`:

- `docs/agent_meshcore_handoff_*.md` (ignore pattern)
- `!docs/agent_meshcore_handoff_v54.md` (explicit include for this file)

---

## Quick “where to start reading code” map

For mesh advert ingest:

- `MeshBtConnectionManager.handleCompanionPacket`
- `MeshBtConnectionManager.parseMeshAdvert`
- `MeshBtConnectionManager.requestFullContactForAdvertRefresh`

For map render + marker metadata:

- `UVProMapComponent.repeaterAdvertListener`
- `UVProMapComponent.meshAdvertListener`
- `CotBridge.markMeshRepeaterMapItem`
- `CotBridge.setMeshMarkerDetails`

For mesh contact UX/actions:

- `MeshDetailsDropDownReceiver`
- `UVProContactHandler.promoteMeshFavoriteContactByUid`

For chat/routing:

- `ChatBridge.resolveRfWireDestination`
- `ChatBridge.inboundRfDestinationLooksLikeSelf`
- `CotBridge.isBtechOutboundChatDestination`

For transport interaction policy:

- `BtConnectionManager.scheduleReconnect`
- `BtConnectionManager.setReconnectBlocker`
- `UVProMapComponent` reconnect blocker wiring

