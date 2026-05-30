# UV-PRO v1.9.51 — Integration & Field Test Plan

> **Purpose:** Single reference for multi-transport integration work (WiFi, UV-PRO RF, MeshCore).  
> **Last updated:** 2026-05-30  
> **Plugin:** UV-PRO **1.9.51** · ATAK CIV **5.5.1.8** · serverless multicast/P2P (no TAK server)  
> **Repo:** `/home/paul/Documents/ATAK/Plugins/Darksteal/`  
> **Rule:** Transport-agnostic fixes — validate on **both** UV-PRO and MeshCore (`.cursor/rules/transport-agnostic-routing.mdc`).

---

## Status legend

| Mark | Meaning |
|------|---------|
| ✅ | Done — implemented **and** field-verified (or explicitly accepted) |
| 🔧 | Implemented in code — **not yet** field-verified on all required transports |
| 🧪 | Ready to test — build deployed, procedure defined |
| ⬜ | Not started |
| ⚠️ | Partial / known gap |
| 🚫 | Shelved — operator decision; not active in current builds |

Update this file as phases complete. Agents: **edit checkboxes here** instead of inferring status from chat history.

---

## Current status (2026-05-30)

**Committed baseline:** post-`bc0cc3a` bridge/uplink/merge work + **GeoChat reachability UI removed**.

| Area | Status | Notes |
|------|--------|-------|
| Contact merge / single marker | 🔧 | Collapse + orphan cleanup in code; partial field verify |
| WiFi baseline (3-device) | ⚠️ | Works intermittently; cold-start discovery 3–5+ min |
| SA Relay network → RF | 🔧 | Filter + signature throttle in code |
| RF → TAK uplink + keepalive | 🔧 | `RfTakUplinkKeepalive` + `dispatchToBroadcast`; needs retest |
| Relayed RF peer on WiFi contact row | 🔧 | `ensureInboundNetworkSaContact()` on J15 — SMKY showed on map/list after uplink |
| GeoChat reachability UI (red X) | 🚫 | **Removed 2026-05-30** — all contacts use native chat bubble; delivery not guaranteed cross-transport |
| Reachability backend (SA relay filter) | 🔧 | `ContactReachability.shouldSaRelayNetworkSa()` still active |
| WiFi contact keepalive | 🔧 | 60s unicast SA — not log-verified |
| MeshCore parity | 🔧 | Transport up; reachability hook rolled back with UI |
| Outbound exclusive routing (P1) | ⬜ | Not started |

**Bridge settings (J25 only, for SMKY ↔ J15 path):** SA Relay **ON**, RF → TAK Uplink **ON**.

**Operator policy:** `adb install -r` to reload plugin; restart ATAK only when explicitly requested.

---

## Test fleet

| Callsign | adb serial | Role |
|----------|------------|------|
| **JESTER_25** | `ZA223F72K9` | Bridge / observer (SA Relay + RF→TAK uplink host) |
| **SMOKEY_15** | `ZT4229JR78` | RF-only (WiFi off) |
| **JESTER_15** | `ZA223DT6J9` | WiFi-only (radio off) |

**Deploy:** `adb -s <serial> install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-UVPro-1.9.51-*-civ-debug.apk`  
**Restart ATAK:** `adb shell am force-stop com.atakmap.app.civ && adb shell monkey -p com.atakmap.app.civ -c android.intent.category.LAUNCHER 1`

**Log capture (example):**
```bash
LOGDIR="/home/paul/Documents/ATAK/test-logs/phaseN-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$LOGDIR"
adb -s ZA223F72K9 logcat -c
adb -s ZA223F72K9 logcat -v threadtime | grep -iE 'UVPro\.(ChatBridge|CotBridge|Router|Reachability|WifiKeepalive|RfTakUplink|MeshBLE)' > "$LOGDIR/J25.log" &
```

---

## Transport policy (design intent)

| Toggle / path | Intended outbound behavior |
|---------------|----------------------------|
| **WiFi ON**, no RF/Mesh | Native ATAK WiFi/TAK only; plugin does not RF-relay |
| **WiFi OFF**, RF connected (UV-PRO or Mesh per toggle) | RF/Mesh only; block native WiFi sends |
| **WiFi ON + RF/Mesh connected** | **Exclusive pick** per message: WiFi **or** off-network, never both |
| **Nothing connected** | Fail visibly; no silent drop |
| **Contacts** | One row + **one map marker** per callsign; transport invisible in UI |
| **GeoChat UI** | Native chat bubble for all contacts (reachability restriction **disabled** — operator accepts undelivered messages) |
| **SA Relay filter** | WiFi-only peer SA still **not** rebroadcast to RF when endpoint/opaque-UID rules match |

---

## Phase overview (re-assessed 2026-05-30)

| Phase | Focus | Code | Field test |
|-------|--------|------|------------|
| **0** | Analysis & transport policy (Q1–Q4) | ✅ | ✅ (design locked) |
| **1** | P0 contact merge + single map marker | ✅ | ⚠️ partial |
| **2** | WiFi baseline (3-device, radios off) | — | ⚠️ partial |
| **3** | SA Relay + RF→TAK bridge | 🔧 | 🧪 **next** |
| **4** | Ghost contacts + transit DM fixes | 🔧 | 🧪 |
| **5** | Reachability UI (WiFi / RF / Mesh) | 🚫 | 🚫 shelved |
| **5b** | Reachability backend (SA relay only) | 🔧 | 🧪 |
| **6** | WiFi contact keepalive (60s unicast SA) | 🔧 | ⬜ |
| **7** | MeshCore transport parity | 🔧 | ⬜ |
| **8** | P1 outbound routing (toggle-driven send path) | ⬜ | ⬜ |
| **9** | P2 polish (split mesh/UV-PRO registry, SA relay→mesh) | ⬜ | ⬜ |

---

## Phase 0 — Analysis & policy ✅

- [x] Multi-transport code review (`ChatBridge`, `CotBridge`, `PacketRouter`, toggles)
- [x] Transport policy confirmed (Q1=A exclusive send, Q2=A, Q3=registry on merge, Q4=one map marker)
- [x] Roadmap documented before implementation

---

## Phase 1 — P0: Contact merge & single map marker

**Goal:** One contact row and one map marker per callsign; merge on WiFi ingress; RF registry on merge.

### Implementation

- [x] `scheduleContactMergeForNetworkContact()` — debounced collapse on WiFi SA/chat
- [x] `finishContactMerge()` → `CotBridge.registerMergedContact()` + orphan cleanup
- [x] `injectPositionCotAtMapUid()` — position at canonical UID (WiFi wins)
- [x] Orphan `ANDROID-*` marker/contact removal when canonical differs
- [x] Collapse on UV-PRO connect, plugin init, RF GPS/chat
- [x] Collapse on **MeshCore** connect (`UVProMapComponent` listener)

### Field verification

- [x] Build installed on all three devices
- [ ] **1.1** WiFi-only: all three see each other; one row per callsign
- [ ] **1.2** J15 radio OFF: J25+SMKY still see J15 on WiFi; one marker
- [ ] **1.3** RF then WiFi: J25↔SMKY merge — no duplicate `ANDROID-*` marker when WiFi peer exists
- [ ] **1.4** Mesh connect triggers collapse (same as UV-PRO connect)

---

## Phase 2 — WiFi baseline (radios off)

**Setup:** All three — WiFi ON, UV-PRO radio OFF, MeshCore disconnected, SA Relay OFF.

- [ ] **2.1** Contacts list: exactly JESTER_25, SMOKEY_15, JESTER_15 (no ghosts)
- [ ] **2.2** J25 → SMKY WiFi DM delivers; SMKY → J25 reply *(N/A if SMKY WiFi off — use J25+J15 only for pure WiFi)*
- [ ] **2.3** J15 visible on map with fresh SA (multicast or keepalive)
- [ ] **2.4** No plugin RF injection when radios off

**Sub-test (J25 + J15 only):** WiFi DMs both directions; one contact row each.

---

## Phase 3 — SA Relay & bridge 🧪 **NEXT**

**Bridge device:** JESTER_25 only. Settings under **UV-PRO → SA Relay**.

| Setting | Default | Bridge use |
|---------|---------|------------|
| Enable SA Relay | OFF | **ON** on J25 |
| RF → TAK Uplink | OFF | **ON** on J25 |
| Restrict GeoChat to reachable peers | — | **Removed** (no UI toggle) |

### Implementation

- [x] SA Relay: network SA/markers/routes → RF broadcast (30s/UID throttle + signature dedupe)
- [x] SA Relay: **All Chat Rooms** GeoChat forwarded; **directed DMs not relayed**
- [x] SA Relay filter: skip WiFi-only peers (`shouldSaRelayNetworkSa` — TCP endpoint / opaque WiFi UID)
- [x] RF → TAK uplink: inbound RF CoT/chat → TAK network
- [x] `RfTakUplinkKeepalive` — rebroadcast known RF peer SA to TAK every 60s (first tick 5s)
- [x] `dispatchRfPeerSaToTakNetwork()` uses `CoTSendMethod.ANY` (multicast to LAN)
- [x] `ensureInboundNetworkSaContact()` — WiFi device registers relayed RF peer in Contacts (map + row)
- [x] `markRfNativeContact` / `markRfHeardCallsign` on RF GPS (`PacketRouter`)

### Field verification

- [ ] **3.1** SA Relay ON (J25): SMKY receives **RF-native** peer SA (J25 position on RF)
- [ ] **3.2** RF→TAK uplink ON: SMKY SA appears on **J15** map within ~5 min (keepalive + multicast)
- [ ] **3.3** J15 contact list shows **SMOKEY_15** (native chat icon — not red X)
- [ ] **3.4** J15 (WiFi-only) SA **not** rebroadcast to SMKY RF
- [ ] **3.5** All Chat Rooms from WiFi appears on SMKY RF
- [ ] **3.6** Directed WiFi DM J25→J15 **not** leaked to SMKY RF
- [ ] **3.7** J25 reachable from J15 via WiFi DM; J25 reachable from SMKY via RF DM
- [ ] **3.8** J15↔SMKY: chat may be attempted (native bubble) — **document whether delivery succeeds** (no UI block)

**Logs to watch:** `RF -> TAK broadcast uplink`, `RF→TAK uplink keepalive`, `ensureInboundNetworkSaContact`, `SA Relay skip WiFi-only`

---

## Phase 4 — Ghost contacts & transit DM fixes

**Goal:** No abbreviated ghost rows (e.g. `SMKY15`, `ANDROID-SMKY15`) from bridge transit traffic.

### Implementation

- [x] Inbound RF DM: transit check **before** `ensurePluginChatContact()`
- [x] `removeOrphanSyntheticRadioContacts()` on startup collapse
- [x] Inbound network GeoChat filter: ignore overheard DMs not for local device
- [x] Directed GeoChat not relayed over RF

### Field verification

- [ ] **4.1** SMKY→J15 DM via J25 bridge: J25 does **not** spawn `SMKY15` ghost
- [ ] **4.2** J15→SMKY WiFi DM: J25 does **not** show/plugin-ingest conversation
- [ ] **4.3** J15↔J25 RF DMs still work when intended
- [ ] **4.4** After restart: orphan synthetics cleaned from contact list

---

## Phase 5 — Reachability UI 🚫 **SHELVED (2026-05-30)**

**Operator decision:** Remove position-only red X and GeoChat restriction. All contacts show native chat bubble; undelivered cross-transport messages are accepted.

### What was tried

- `ContactReachability` WiFi/RF path classification + `PositionOnlyConnector` red X
- Direct LAN IP ownership (`isDirectLanWifiPeer`) for J25 on J15
- Partial success: SMKY unreachable on J15 worked; J25↔J15 and false positives on RF/WiFi cross-view persisted

### Current code state

- [x] `isPolicyEnabled()` → always **false**
- [x] `applyContactCommsPolicy()` → restores native GeoChat; strips any legacy position-only connector
- [x] Preference + dropdown toggle **removed**
- [x] `UVProContactHandler` — no unreachable intercept; always opens chat
- [ ] Dead code retained: `PositionOnlyConnector`, `ContactConnectorIcons`, `preferPositionOnlyContactActionInternal` (cleanup optional in Phase 9)

### Field verification

- [x] **5.1** All contacts show chat bubble after restart (2026-05-30)
- [ ] **5.2** Re-open reachability UI only if operator requests (future phase)

---

## Phase 5b — Reachability backend (SA relay filter only) 🧪

**Goal:** Backend classification still prevents WiFi-only ghosts on RF relay — **no contact-list UI impact**.

- [x] `shouldSaRelayNetworkSa()` — skip peers with `contact@endpoint` or opaque WiFi UID
- [x] `isInboundNetworkGeoChatForLocalDevice()` — bridge overhear filter
- [x] `isLocalWifiAvailable()` — real WiFi transport check (ConnectivityManager)
- [ ] **5b.1** Confirm J15 SA never appears on SMKY RF when J25 SA Relay ON
- [ ] **5b.2** Confirm J25 SA **does** appear on SMKY RF

---

## Phase 6 — WiFi contact keepalive

**Goal:** Unicast mini self-SA every 60s to WiFi peers when WiFi up (no UI toggle).

### Implementation

- [x] `WifiContactKeepalive.java` + `CotBuilder.buildSelfWifiKeepaliveCot()`
- [x] Wired in `UVProMapComponent` start/stop

### Field verification

- [ ] **6.1** Logcat `UVPro.WifiKeepalive` every ~60s when WiFi up
- [ ] **6.2** Serverless peer stale time improves (120s stale on keepalive SA)
- [ ] **6.3** No keepalive when WiFi down

---

## Phase 7 — MeshCore transport parity

**Goal:** MeshCore is a first-class routing path (shared `PacketRouter`).

### Implementation

- [x] MeshCore BLE transport (`MeshBtConnectionManager`)
- [x] ATAK_DATA channel (slot 7) for CoT/chat envelopes
- [x] Beacon/ping follow active transport (UV-PRO preferred, else Mesh)
- [ ] Mesh reachability hook (deferred with Phase 5 UI)
- [ ] Inbound transport tagging (`routeIncoming(..., MESH)` vs UV-PRO) — Phase 9

### Field verification

- [ ] **7.1** Two devices MeshCore connected (WiFi/UV-PRO off to peer): chat + SA work
- [ ] **7.2** Mesh disconnect → chat may fail silently (no reachability UI)
- [ ] **7.3** Mesh reconnect → chat works after mesh SA/chat received
- [ ] **7.4** DM, All Chat Rooms, contact-targeted CoT, broadcast CoT on Mesh
- [ ] **7.5** UV-PRO + Mesh both connected: transmit toggle selects active manager

---

## Phase 8 — P1: Outbound transport routing ⬜

**Goal:** Send path follows connection toggles; no duplicate WiFi+RF sends.

- [ ] Wire **ATAK WiFi Transmit** toggle into outbound block/allow
- [ ] Exclusive routing: WiFi **or** UV-PRO/Mesh per message when multiple paths up
- [ ] Visible failure when no path (toast / failed send state)
- [ ] Field matrix: all toggle combinations × {DM, All Chat Rooms, point CoT, broadcast CoT}
- [ ] MeshCore parity for every routing change

---

## Phase 9 — P2: Polish & hardening ⬜

- [ ] Remove dead reachability UI code (`PositionOnlyConnector`, icons, prefs)
- [ ] Split `meshContactUids` vs `uvProContactUids`
- [ ] SA Relay → Mesh path policy
- [ ] Faster bridge discovery (shorter keepalive interval, bootstrap on connect)
- [ ] Regression suite documented after each release

---

## Recommended test order (2026-05-30)

1. **Deploy** latest debug APK → restart ATAK on all three  
2. **Phase 3** SA Relay + RF→TAK uplink (J25 bridge settings ON) — **priority**  
3. **Phase 5b** confirm WiFi-only SA not relayed to RF  
4. **Phase 4** transit DM / ghost regression  
5. **Phase 2** WiFi baseline smoke (J25+J15)  
6. **Phase 1** merge spot-checks if duplicate markers seen  
7. **Phase 6** keepalive log verification  
8. **Phase 7** when Mesh hardware available  
9. **Phase 8–9** after P1 implementation  

---

## Key source files (for agents)

| Area | Files |
|------|--------|
| Reachability (backend) | `contacts/ContactReachability.java` |
| RF→TAK uplink | `network/RfTakUplinkKeepalive.java`, `cot/CotBridge.java` |
| Chat / merge | `chat/ChatBridge.java`, `chat/GeoChatContactListHelper.java` |
| CoT / SA Relay | `cot/CotBridge.java`, `cot/CotBuilder.java` |
| Routing | `protocol/PacketRouter.java` |
| Mesh | `bluetooth/MeshBtConnectionManager.java` |
| WiFi keepalive | `network/WifiContactKeepalive.java` |
| Lifecycle | `UVProMapComponent.java`, `UVProDropDownReceiver.java` |
| Contact tap | `UVProContactHandler.java` |

---

## Session notes

| Date | Note |
|------|------|
| 2026-05-29 | Reachability + mesh hook built; phased WiFi/SA Relay testing started |
| 2026-05-29 | User confirmed J15↔J25 DM leak fixed (prior SA Relay GeoChat change) |
| 2026-05-30 | RF→TAK uplink keepalive + `dispatchToBroadcast` fix; `ensureInboundNetworkSaContact` |
| 2026-05-30 | Reachability red-X UI **removed** per operator; native chat for all contacts |
| 2026-05-30 | Committed bridge/uplink/merge session work; Phase 3 field test is next |

---

## Agent checklist (start of session)

- [ ] Read this file + `docs/UV-PRO-v1.9.51-AGENT-HANDOFF.md`
- [ ] Confirm `adb devices` and callsign ↔ serial mapping
- [ ] Confirm APK version on devices matches latest local build
- [ ] Update checkboxes in this file when tests pass/fail
- [ ] Log paths under `/home/paul/Documents/ATAK/test-logs/`
