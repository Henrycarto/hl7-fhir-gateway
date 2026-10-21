# hl7-fhir-gateway

[![ci](https://github.com/Henrycarto/hl7-fhir-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/Henrycarto/hl7-fhir-gateway/actions/workflows/ci.yml)
[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/Henrycarto/hl7-fhir-gateway)

A Spring Boot service that receives HL7 v2 messages from hospital systems and writes them to a FHIR R4 server.

Hospitals still run on HL7 v2. Admissions, discharges and lab results travel between systems as pipe-delimited messages over MLLP. New applications want FHIR. This gateway sits between the two worlds and is built around the failure modes that matter in production:

- **Senders resend.** Interface engines retry on timeout. The same message arriving twice must not create two patients or two lab results.
- **The FHIR server goes down.** The sender should still get a fast ACK, and nothing may be lost while the server is unavailable.
- **Order matters.** A discharge applied before its admission leaves the encounter in the wrong state.
- **Some messages are simply wrong.** They need a clear rejection and a place where an engineer can see and replay them.

![Demo: admit, lab result, resend, discharge](docs/demo.gif)

## What it handles

| HL7 v2 | FHIR R4 |
|---|---|
| ADT^A01 admit, ADT^A04 register | Patient (upsert) + Encounter `in-progress` (upsert) |
| ADT^A03 discharge | Patient + Encounter `finished`, period end set |
| ADT^A08 update patient | Patient only |
| ORU^R01 lab result | Patient (create if missing) + one Observation per OBX |

Messages v2.3 to v2.5.1 are read into the v2.5 model. Anything else gets an `AR` ACK.

## How it works

```
 MLLP :2575 ─┐
             ├─> IngestService ──> [inbound_message + outbox_entry]  ──> DeliveryWorker ──> FHIR server
 POST /api/hl7┘     parse, dedupe,     one DB transaction                  in order,          transaction
                    map, ACK                                               retry, dead letter  bundle
```

1. **Ingest.** The message is parsed with HAPI HL7v2. MSH-3/4/10 (sender plus control id) is checked against what has already been accepted; a resend is acknowledged `AA` and nothing new is queued.
2. **Map.** The message becomes one FHIR transaction bundle. Every write is keyed by a business identifier (MRN, visit number, a stable observation key), never by a server id:
   - Patient and Encounter use conditional update: `PUT Patient?identifier=system|value`.
   - In lab results the Patient uses conditional create (`POST` with `ifNoneExist`), so a thin PID segment in an ORU never overwrites the full demographics that came in through ADT.
   - Observation identifiers are `facility|filler order|OBX set id|code`, so a corrected result (OBX-11 = `C`) replaces the original instead of duplicating it.
3. **Store and ACK.** The raw message and the bundle are written to the database in one transaction, then the sender gets `AA`. `AA` means "stored and queued", not "written to FHIR". The sender never waits on the FHIR server.
4. **Deliver.** A scheduled worker sends bundles oldest first.
   - 5xx, 429, timeouts, connection refused: exponential backoff (5 s doubling to 10 min), and the queue **stops** so later messages do not overtake.
   - 4xx (the server rejected the content): straight to `DEAD` with the OperationOutcome stored, and the queue moves on. Retrying it would only block everything behind it.
   - After `max-attempts` retryable failures an entry also goes to `DEAD`.

### ACK codes

| Situation | MSA-1 | HTTP (for `/api/hl7`) |
|---|---|---|
| Accepted, or a duplicate of something already accepted | `AA` | 200 |
| Supported type, missing required data (no PID-3, no PV1-19) | `AE` | 422 |
| Unsupported type, or not parseable as HL7 | `AR` | 400 |

A message answered with `AE` is not recorded, so the sender can fix the data and resend with the same control id.

## Try it in your browser

Nothing to install:

1. Click **Open in GitHub Codespaces** above and wait for the build. The Codespace comes with Java 21, Maven and a HAPI FHIR server.
2. Start the gateway: `mvn spring-boot:run`
3. In a second terminal, run the demo: `./scripts/demo.sh`

The demo walks through an admission, a lab result, a resend, a rejected message and a discharge, then shows what the FHIR server holds. The integration test needs Docker and runs in CI, not in the Codespace.

## Run it

Requirements: Java 21, Maven, Docker (for the local FHIR server).

```bash
docker compose up -d          # HAPI FHIR JPA server on http://localhost:8090/fhir
mvn spring-boot:run           # gateway: HTTP on :8080, MLLP on :2575
```

The quickest way to see everything is the demo script, in a second terminal:

```bash
./scripts/demo.sh
```

Send a message over HTTP:

```bash
curl -s -X POST localhost:8080/api/hl7 -H 'Content-Type: text/plain' --data-binary @samples/adt_a01.hl7
```

Or over MLLP:

```bash
./samples/send-mllp.sh samples/oru_r01.hl7
```

Then check the result:

```bash
curl -s 'localhost:8090/fhir/Patient?identifier=4711'
curl -s localhost:8080/api/outbox/summary
```

Operational endpoints:

| Endpoint | Purpose |
|---|---|
| `GET /api/outbox/summary` | Counts of pending, sent and dead entries |
| `GET /api/outbox/dead` | Last 100 dead entries with the server's error |
| `POST /api/outbox/{id}/retry` | Put a dead entry back in the queue after fixing the cause |
| `GET /actuator/health` | Liveness |

Configuration lives in `application.yml` under `gateway:`: FHIR base URL, identifier systems, the default timezone for HL7 timestamps without an offset (Europe/Vienna by default), MLLP port, retry policy. Run with `--spring.profiles.active=postgres` and `DATABASE_URL` to use PostgreSQL instead of the file-based H2.

## Tests

```bash
mvn test                  # unit and Spring tests, no Docker needed
mvn verify -Pintegration  # the integration test, against a real HAPI FHIR server in Docker
```

Last run: 34 tests, 0 failures (mvn test), plus 1 integration test against HAPI FHIR 7.4.

Covered:

- Timestamp conversion, including precision and daylight saving for messages without an offset.
- ADT and ORU mapping field by field, including the stable identifiers that make replays safe.
- Idempotency: the same control id twice is one database row and one outbox entry.
- ACK behaviour for accepted, malformed, unsupported and incomplete messages.
- Delivery: retry with backoff, head-of-line blocking on outage, dead letter on rejection, max attempts, replay through the API.
- MLLP end to end over a real socket with HAPI's client.
- Integration: A01, ORU, A03 sent twice to a real FHIR server leave exactly one Patient, one finished Encounter and four Observations, and the ORU did not wipe the ADT address.

## Known limits

These are deliberate scope cuts, not oversights:

- **Single instance.** The delivery worker assumes one gateway process. Running several would need row locking (`SELECT ... FOR UPDATE SKIP LOCKED`) or a broker.
- **Global ordering.** The queue is strictly first in, first out. One stuck message for one patient delays everyone. Per-patient queues would fix that at the cost of more complexity.
- **No DiagnosticReport.** Lab results become Observations only. Grouping them under a DiagnosticReport per OBR is the obvious next step.
- **No merges.** ADT^A40 (patient merge) is rejected. Merges need the FHIR `$merge` operation or link handling, which differs between servers.
- **No authentication** on the HTTP endpoints or the FHIR client. In a hospital this runs inside the integration network; adding mTLS or SMART backend services auth to the client is a configuration exercise.
- **Sample data only.** All patients in `samples/` are fictional.

## Stack

Java 21, Spring Boot 3.3, Spring Data JPA, HAPI HL7v2 2.5.1, HAPI FHIR 7.4 (R4), H2 or PostgreSQL, JUnit 5, Testcontainers.
