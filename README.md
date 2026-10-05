# Offline UPI Mesh

I built this to answer one question: what happens when you want to pay someone over UPI and neither of you has any internet? My answer is a Spring Boot backend where the payment hops phone-to-phone over a simulated Bluetooth mesh until one of those phones gets a connection and uploads it. The backend then decrypts it, checks it hasn't already been settled, and processes it.

This repo is the server side, plus a simulator for the mesh so I can demo the whole flow on one laptop without real Bluetooth hardware or a pile of phones.

**Stack:** Java 17, Spring Boot 3.3.5, Spring Data JPA (Hibernate), Thymeleaf, Maven. H2 by default, MySQL through a Spring profile.

## What it does

Three things, end to end:

- A payment can pass through untrusted intermediate phones without any of them being able to read or tamper with it (hybrid RSA + AES-GCM encryption).
- If the same payment reaches the backend more than once (e.g. delivered by multiple bridge devices at the same time), it only settles once. I verify this with a concurrency test that fires 100 threads at the same packet simultaneously.
- A tampered or replayed packet gets rejected before it touches the ledger.

## Running it

You need JDK 17+ on your PATH. That's it. Maven comes bundled through the wrapper, and the default setup uses H2 in memory, so there's no database to install.

```bash
# Windows
mvnw.cmd spring-boot:run

# Mac/Linux
./mvnw spring-boot:run
```

The first run downloads Maven and the dependencies (~90MB), which takes a couple of minutes. After that it starts in a few seconds. Once it's up, open `http://localhost:8080` for the dashboard.

Run the tests with `mvnw.cmd test` (or `./mvnw test`). The one worth looking at is `IdempotencyConcurrencyTest`.

## Running it with MySQL

H2 is great for a quick demo, but it forgets everything when the app stops. I also wanted to run the project against a real database, so there's a `mysql` Spring profile (`application-mysql.properties`) that swaps H2 out. Nothing else in the code changes.

**1. Start MySQL.** The easiest way is the Docker Compose file in the repo root:

```bash
docker compose up -d
```

This starts MySQL 8.4 with a database called `upimesh` (user `upimesh`, password `upimesh`) and keeps the data in a Docker volume. If you'd rather use a MySQL you already have installed, just make sure the settings below match it.

**2. Start the app with the profile turned on.**

```bash
# Mac/Linux
./mvnw spring-boot:run -Dspring-boot.run.profiles=mysql

# Windows (PowerShell)
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mysql"
```

The tables are created automatically on first start and the 4 demo accounts are seeded once. Restart the app and your data is still there.

**Settings.** Everything has a default that matches `docker-compose.yml`, and any of it can be overridden with an environment variable:

| Variable | Default | What it is |
|---|---|---|
| `MYSQL_HOST` | `localhost` | MySQL host |
| `MYSQL_PORT` | `3306` | MySQL port |
| `MYSQL_DB` | `upimesh` | database name |
| `MYSQL_USER` | `upimesh` | username |
| `MYSQL_PASSWORD` | `upimesh` | password |
| `DDL_AUTO` | `update` | Hibernate schema mode. `update` keeps data, `create` rebuilds the schema on every start |

**Running the tests against MySQL.** The tests use H2 unless you say otherwise. To run them on MySQL:

```bash
# Mac/Linux
SPRING_PROFILES_ACTIVE=mysql DDL_AUTO=create ./mvnw test

# Windows (PowerShell)
$env:SPRING_PROFILES_ACTIVE="mysql"; $env:DDL_AUTO="create"; .\mvnw.cmd test
```

I use `DDL_AUTO=create` here on purpose. The concurrency test debits `alice@demo` by 100 every time it runs, so on a database that keeps its data she would eventually run out of money. `create` gives every run a fresh schema, which also means it wipes the tables in that database.

To stop MySQL: `docker compose down` (keeps data) or `docker compose down -v` (deletes data).

## Why H2 by default and MySQL as an option

- **H2 is the default** because this is a demo. Anyone who clones the repo can run it immediately, the data is meant to be thrown away anyway, and the tests start fast.
- **MySQL is there** for when I want data to survive a restart, or when I want to check the idempotency logic against a real database engine. The unique constraint on `packet_hash` is the last line of defence against double settlement, and it's more convincing to see it hold on InnoDB than on an in-memory database.
- Switching is purely configuration. There's no H2-specific SQL anywhere, everything goes through JPA.

## Demo flow

The dashboard has 4 buttons that walk through the pipeline:

1. **Inject into Mesh** builds a `PaymentInstruction`, encrypts it with the server's RSA public key, wraps it in a `MeshPacket`, and hands it to a virtual sender phone.
2. **Run Gossip Round**: every device holding the packet broadcasts it to every other device (TTL decrements each hop). A couple of rounds and every device has it.
3. **Bridges Upload to Backend**: the one virtual device with internet access POSTs whatever it's holding to `/api/bridge/ingest`. This runs: hash the ciphertext → try to claim the hash → decrypt → check freshness → settle in a DB transaction.
4. To actually see the idempotency guarantee in action (not just simulate it through the UI), run:
   ```
   mvnw.cmd test -Dtest=IdempotencyConcurrencyTest#singlePacketDeliveredByHundredBridgesSettlesExactlyOnce
   ```
   100 threads hit `BridgeIngestionService.ingest()` with the same packet at the same time. Exactly one settles, the other 99 get dropped as duplicates, and the sender's balance only moves once.

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
      │
      ▼
  H2 (default)  or  MySQL (profile "mysql")
```

## The three problems this solves

**Untrusted intermediaries.** A stranger's phone is carrying your transaction, so they shouldn't be able to read or change it. The sender encrypts with the server's public key using hybrid encryption: generate a fresh AES-256 key per packet, encrypt the payload with AES-256-GCM, encrypt just the AES key with RSA-OAEP, and concatenate the two. RSA alone can't handle payloads over ~245 bytes at 2048-bit, hence the hybrid approach, which is the same pattern TLS uses. GCM is authenticated encryption, so a single flipped bit anywhere in the ciphertext throws on decrypt instead of silently corrupting data.

**Duplicate delivery.** If the same packet reaches the backend through multiple bridges at once, a naive implementation double-processes it. My fix is to hash the ciphertext (`SHA-256`) and atomically claim it with `ConcurrentHashMap.putIfAbsent` before doing anything else. The first caller gets `null` back and proceeds, everyone else gets the existing entry and is dropped as a duplicate. I hash the ciphertext specifically (not the packetId, which an intermediate could rewrite, and not the cleartext, which needs decryption first) so dedup happens before spending any CPU on RSA. In production this would become `SET key NX EX 86400` in Redis. There's also a unique DB constraint on `packet_hash` as a second layer in case the cache ever misses.

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

src/main/resources/
├── application.properties          default config (H2)
├── application-mysql.properties    overrides for the "mysql" profile
└── templates/dashboard.html

src/test/java/com/demo/upimesh/IdempotencyConcurrencyTest.java
docker-compose.yml                  MySQL 8.4 for the "mysql" profile
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
| GET | `/h2-console` | inspect the in-memory DB (`jdbc:h2:mem:upimesh`, user `sa`, no password). Default profile only, it's turned off under `mysql` |

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

All three live in `IdempotencyConcurrencyTest`:

- `encryptDecryptRoundTrip` checks that encryption and decryption are symmetric.
- `tamperedCiphertextIsRejected` flips a byte in the ciphertext and checks it returns `INVALID` instead of crashing.
- `singlePacketDeliveredByHundredBridgesSettlesExactlyOnce` fires 100 threads at one packet at the same moment. Exactly 1 `SETTLED`, 99 `DUPLICATE_DROPPED`, sender debited once, receiver credited once.

## What's simulated vs what's not

The crypto and idempotency logic is close to production-ready. Everything else is stubbed so it runs on a single laptop:

- H2 in memory by default, MySQL optional, but no replicas, backups or connection tuning
- `ConcurrentHashMap` instead of Redis for the idempotency cache (so dedup only works inside one running instance)
- RSA keypair regenerated on every startup instead of living in an HSM/KMS
- Mesh gossip is simulated in `MeshSimulatorService` instead of real BLE/Wi-Fi Direct
- No auth on `/api/bridge/ingest`, it would need mTLS or signed bridge certs
- Accounts are seeded by the app, no real KYC/VPA/PIN verification
- No rate limiting

## Limitations of the design itself

These aren't bugs, they come from routing payments when there's no connectivity anywhere in the chain:

- The receiver can't verify the sender actually has the funds at send time, so it's effectively an IOU until it reaches the backend. If the sender's balance is empty by then, the receiver is out of luck. Real offline UPI (UPI Lite) avoids this with a pre-funded, hardware-backed wallet.
- A malicious sender with ₹500 could send it to two different people in two different offline pockets. Whichever packet reaches the backend first wins and the other gets rejected. Same root cause as above.
- Real BLE gossip between strangers' phones is a lot harder than this demo makes it look. Background BLE is throttled on Android, iOS locks down peripheral mode, and getting two phones to reliably connect without the app being open is its own project.
- A stranger's phone carries your encrypted packet even though they can't read it. That's still metadata worth thinking about in a real deployment.

## Troubleshooting

- `java: command not found` → install JDK 17+
- port 8080 in use → change `server.port` in `application.properties`
- first `mvnw.cmd` run takes forever → it's downloading Maven + deps, give it a couple of minutes
- PowerShell says `mvnw.cmd` isn't recognized → run `.\mvnw.cmd spring-boot:run`
- concurrency test flakes → it's timing-sensitive, rerun a couple of times
- MySQL: `Communications link failure` → the container isn't ready yet, check `docker compose ps` and wait for it to say `healthy`
- MySQL: `Access denied for user` → the credentials don't match. If you changed the password in `docker-compose.yml` after the first start, run `docker compose down -v` so MySQL re-initialises with the new one
- MySQL: port 3306 already in use → run both Docker and the app with `MYSQL_PORT=3307` (or whichever port is free)
- test fails on MySQL with an insufficient balance → you ran it without `DDL_AUTO=create` and the demo account has been drained by earlier runs

## License

No license, use it however.
