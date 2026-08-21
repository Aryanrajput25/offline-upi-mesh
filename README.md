# Offline UPI Mesh

A Spring Boot backend for offline peer-to-peer UPI payments — you're somewhere with zero connectivity, you send ₹500 to a friend, and the payment hops phone-to-phone over a simulated Bluetooth mesh until one of those phones gets internet and uploads it to this backend. The backend decrypts it, makes sure it hasn't already been settled, and processes it.

This repo is the server side, plus a simulator for the mesh so the whole flow can be demoed on one laptop without needing real Bluetooth hardware or multiple devices.

## What it actually does

Three things, end to end:

- A payment can pass through untrusted intermediate phones without any of them being able to read or tamper with it (hybrid RSA + AES-GCM encryption).
- If the same payment reaches the backend more than once (e.g. delivered by multiple bridge devices at the same time), it only settles once — verified with a concurrency test that fires 3 threads at the same packet simultaneously.
- A tampered or replayed packet gets rejected before it touches the ledger.

## Running it

Needs JDK 17+ on PATH. Nothing else — no DB or Redis to install, H2 runs in-memory and Maven is bundled via the wrapper.

```bash
# Windows
mvnw.cmd spring-boot:run

# Mac/Linux
./mvnw spring-boot:run
```

First run pulls Maven + dependencies (~90MB total), takes a couple minutes. After that it starts in a few seconds. Once it's up, go to `http://localhost:8080` for the dashboard.

Run the tests with `mvnw.cmd test` (or `./mvnw test`). The one worth looking at is `IdempotencyConcurrencyTest`.

## Demo flow

The dashboard has 4 buttons that walk through the pipeline:

1. **Inject into Mesh** — builds a `PaymentInstruction`, encrypts it with the server's RSA public key, wraps it in a `MeshPacket`, hands it to a virtual sender phone.
2. **Run Gossip Round** — every device holding the packet broadcasts it to every other device (TTL decrements each hop). A couple rounds and every device has it.
3. **Bridges Upload to Backend** — the one virtual device with internet access POSTs whatever it's holding to `/api/bridge/ingest`. This runs: hash the ciphertext → try to claim the hash → decrypt → check freshness → settle in a DB transaction.
4. To actually see the idempotency guarantee in action (not just simulate it through the UI), run:
   ```
   mvnw.cmd test -Dtest=IdempotencyConcurrencyTest#singlePacketDeliveredByThreeBridgesSettlesExactlyOnce
   ```
   Three threads hit `BridgeIngestionService.ingest()` with the same packet at the same time. Exactly one settles, the other two get dropped as duplicates, and the sender's balance only moves once.

## Architecture

```
SENDER PHONE (offline)
  PaymentInstruction { sender, receiver, amount, pinHash, nonce, time }
      │ encrypt with server's RSA public key
      ▼
  MeshPacket { packetId, ttl, createdAt, ciphertext }
      │ Bluetooth gossip, hop by hop
      ▼
  bridge device walks outside, gets 4G
      │ HTTPS POST
      ▼
SPRING BOOT BACKEND
  /api/bridge/ingest
      1. hash ciphertext (SHA-256)
      2. IdempotencyService.claim(hash) — atomic putIfAbsent, duplicates rejected here
      3. HybridCryptoService.decrypt() — RSA-OAEP unwraps AES key, AES-GCM decrypts + verifies auth tag
      4. freshness check — signedAt within 24h
      5. SettlementService.settle() — @Transactional debit/credit + ledger write, @Version for optimistic locking
```

## The three problems this solves

**Untrusted intermediaries.** A stranger's phone is carrying your transaction — they shouldn't be able to read or change it. Sender encrypts with the server's public key using hybrid encryption: generate a fresh AES-256 key per packet, encrypt the payload with AES-256-GCM, encrypt just the AES key with RSA-OAEP, concatenate the two. RSA alone can't handle payloads over ~245 bytes at 2048-bit, hence the hybrid approach — same pattern TLS uses. GCM being authenticated encryption means a single flipped bit anywhere in the ciphertext throws on decrypt instead of silently corrupting data.

**Duplicate delivery.** If the same packet reaches the backend through multiple bridges at once, a naive implementation double-processes it. Fix: hash the ciphertext (`SHA-256`) and atomically claim it with `ConcurrentHashMap.putIfAbsent` before doing anything else — first caller gets `null` back and proceeds, everyone else gets the existing entry and is dropped as a duplicate. Hashing the ciphertext specifically (not the packetId, which an intermediate could rewrite, and not the cleartext, which needs decryption first) means dedup happens before spending CPU on RSA. In production this becomes `SET key NX EX 86400` in Redis. There's also a unique DB constraint on `packet_hash` as a second layer in case the cache ever misses.

**Replay attacks.** Two layers: `signedAt` in the encrypted payload gets checked against a 24h freshness window, and it can't be tampered with without breaking the GCM tag. A nonce in the payload means two legitimate sends of the same amount produce different ciphertexts (so both settle), while an actual replay of a captured packet is byte-identical and gets caught by the idempotency check.

## Project layout

```
src/main/java/com/demo/upimesh/
├── UpiMeshApplication.java
├── model/              Account, Transaction, MeshPacket, PaymentInstruction + repos
├── crypto/
│   ├── ServerKeyHolder.java        RSA-2048 keypair, generated on startup
│   └── HybridCryptoService.java    RSA-OAEP + AES-256-GCM encrypt/decrypt
├── service/
│   ├── DemoService.java            seeds accounts, simulates sender phone
│   ├── VirtualDevice.java          one simulated phone
│   ├── MeshSimulatorService.java   gossip protocol
│   ├── IdempotencyService.java     ConcurrentHashMap-based dedup
│   ├── SettlementService.java      @Transactional debit/credit
│   └── BridgeIngestionService.java the actual pipeline
├── controller/          ApiController, DashboardController
└── config/AppConfig.java

src/test/java/com/demo/upimesh/IdempotencyConcurrencyTest.java
```

## API

| Method | Path | Does |
|---|---|---|
| GET | `/` | dashboard |
| GET | `/api/server-key` | server's RSA public key |
| GET | `/api/accounts` | balances |
| GET | `/api/transactions` | last 20 transactions |
| GET | `/api/mesh/state` | state of every virtual device |
| POST | `/api/demo/send` | simulate sender phone injecting a packet |
| POST | `/api/mesh/gossip` | one round of gossip |
| POST | `/api/mesh/flush` | bridges with internet upload to backend |
| POST | `/api/mesh/reset` | clear mesh + idempotency cache |
| POST | `/api/bridge/ingest` | real bridges post here |
| GET | `/h2-console` | inspect the in-memory DB (`jdbc:h2:mem:upimesh`, user `sa`, no password) |

`/api/bridge/ingest` expects:
```json
{
  "packetId": "550e8400-e29b-41d4-a716-446655440000",
  "ttl": 2,
  "createdAt": 1730000000000,
  "ciphertext": "base64-encoded-RSA-and-AES-blob"
}
```
and returns `{ "outcome": "SETTLED" | "DUPLICATE_DROPPED" | "INVALID", "packetHash": "...", "reason": null, "transactionId": 42 }`.

## Tests

- `encryptDecryptRoundTrip` — encryption/decryption is symmetric
- `tamperedCiphertextIsRejected` — flipping a byte in the ciphertext returns `INVALID` instead of crashing
- `singlePacketDeliveredByThreeBridgesSettlesExactlyOnce` — 3 threads, 1 packet, simultaneous delivery. Exactly 1 `SETTLED`, 2 `DUPLICATE_DROPPED`, sender debited once.

## What's simulated vs what's not

The crypto and idempotency logic is close to production-ready. Everything else here is stubbed for a single-laptop demo:

- H2 in-memory instead of Postgres/MySQL with replicas
- `ConcurrentHashMap` instead of Redis for the idempotency cache
- RSA keypair regenerated on every startup instead of living in an HSM/KMS
- Mesh gossip is simulated in `MeshSimulatorService` instead of real BLE/Wi-Fi Direct
- No auth on `/api/bridge/ingest` — would need mTLS or signed bridge certs
- Accounts are seeded in memory, no real KYC/VPA/PIN verification
- No rate limiting

## Limitations of the design itself

These aren't bugs, they're inherent to routing payments with no connectivity anywhere in the chain:

- The receiver can't verify the sender actually has the funds at send time — it's effectively an IOU until it reaches the backend. If the sender's balance is empty by then, the receiver is out of luck. Real offline UPI (UPI Lite) avoids this with a pre-funded, hardware-backed wallet.
- A malicious sender with ₹500 could send it to two different people in two different offline pockets. Whichever packet reaches the backend first wins, the other gets rejected. Same root cause as above.
- Real BLE gossip between strangers' phones is a lot harder than this demo makes it look — background BLE is throttled on Android, iOS locks down peripheral mode, and getting two phones to reliably form a connection without the app being open is its own project.
- A stranger's phone carries your encrypted packet even though they can't read it — that's still metadata worth thinking about in a real deployment.

## Troubleshooting

- `java: command not found` → install JDK 17+
- port 8080 in use → change `server.port` in `application.properties`
- first `mvnw.cmd` run takes forever → it's downloading Maven + deps, give it a couple minutes
- PowerShell says `mvnw.cmd` isn't recognized → run `.\mvnw.cmd spring-boot:run`
- concurrency test flakes → timing-sensitive, rerun a couple times

## License

No license, use it however.
