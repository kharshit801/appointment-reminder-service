# Appointment Booking / Reminder Service

A small Spring Boot service that books vehicle service appointments and sends
reminder notifications **24 hours** and **2 hours** before each appointment.

The central requirement — *a customer must never receive the same reminder twice,
and it must be provable* — drives the entire design. See
[Why reminders can never be sent twice](#why-reminders-can-never-be-sent-twice).

---

## Contents
- [Tech stack](#tech-stack)
- [Architecture](#architecture)
- [Data model](#data-model)
- [Why reminders can never be sent twice](#why-reminders-can-never-be-sent-twice)
- [How to run](#how-to-run)
- [API](#api)
- [Demo walk-through](#demo-walk-through)
- [Tests](#tests)
- [Design decisions](#design-decisions)
- [Corner cases & failure handling](#corner-cases--failure-handling)
- [Scale](#scale)
- [What I'd do with another week](#what-id-do-with-another-week)
- [Open questions from the brief](#open-questions-from-the-brief)

---

## Tech stack
- **Java 17**, **Spring Boot 3.3**
- **PostgreSQL 16** (via Docker Compose)
- **Flyway** for schema migrations
- **JPA / Hibernate** for persistence
- **JUnit 5 + Testcontainers** (real Postgres) for tests
- **Maven Wrapper** (`./mvnw`) — no local Maven install required

---

## Architecture

```
                 ┌──────────────────────────────────────────────┐
   HTTP          │            appointment-reminder-service        │
 ────────▶  POST │  ┌───────────────┐      ┌────────────────────┐ │
 /appointments   │  │ Appointment   │      │ ReminderPlanner    │ │
                 │  │ Controller    │─────▶│ (materializes the  │ │
                 │  └───────────────┘      │  24h & 2h reminders)│ │
                 │         │               └────────────────────┘ │
                 │         ▼                                       │
                 │  ┌───────────────┐   one transaction           │
                 │  │ Appointment   │  appointment + reminders     │
                 │  │ Service       │─────────────┐               │
                 │  └───────────────┘             │               │
                 │                                ▼               │
                 │  ┌──────────────────────────────────────────┐ │
                 │  │              PostgreSQL                    │ │
                 │  │  appointment                              │ │
                 │  │  reminder  (UNIQUE(appointment_id, lead_time_seconds)) │ │
                 │  └──────────────────────────────────────────┘ │
                 │                                ▲               │
                 │  ┌───────────────┐  poll +     │ claim (FOR    │
                 │  │ Reminder      │  claim due  │ UPDATE SKIP   │
                 │  │ Scheduler     │────────────▶│ LOCKED)       │
                 │  │ (@Scheduled)  │             │               │
                 │  └───────┬───────┘  ┌──────────┴────────────┐  │
                 │          │          │ ReminderDispatchService│  │
                 │          └─────────▶│  PENDING→SENDING→SENT   │  │
                 │                     └───────────┬────────────┘  │
                 │                                 ▼               │
                 │                     ┌────────────────────────┐  │
                 │                     │ NotificationSender      │  │
                 │                     │ (stub: logs payload)    │  │
                 │                     └────────────────────────┘  │
                 └──────────────────────────────────────────────┘
```

A more detailed diagram lives in [`docs/design.md`](docs/design.md).

**Two independent paths:**
1. **Booking (synchronous).** `POST /appointments` writes the appointment *and* its
   reminder rows in a single transaction. The reminders are "materialized" up front
   as rows in the `reminder` table.
2. **Dispatch (asynchronous).** A scheduled poller claims due reminders, hands each
   to the stub `NotificationSender`, and records the outcome. This is a classic
   **transactional outbox / claim-check** pattern.

---

## Data model

**`appointment`**

| column            | notes                                        |
|-------------------|----------------------------------------------|
| `id`              | internal PK                                  |
| `public_id`       | UUID exposed via the API (never leak PKs)    |
| `dealership_id`   | which of the 500 dealerships                 |
| `customer_name`   | display name                                 |
| `customer_contact`| email or phone (the stub sender is agnostic) |
| `scheduled_at`    | appointment time (UTC)                        |
| `status`          | `BOOKED` / `CANCELLED`                        |

**`reminder`** — one row per (appointment, lead time). This table is both the
**work queue** and the **audit log**.

| column          | notes                                                     |
|-----------------|-----------------------------------------------------------|
| `appointment_id`| FK → appointment                                          |
| `lead_time_seconds` | seconds before the appointment (86400 = 24h, 7200 = 2h) |
| `send_at`       | `scheduled_at − lead time` (UTC)                          |
| `status`        | `PENDING` → `SENDING` → `SENT` \| `FAILED`                |
| `attempts`      | number of failed delivery attempts                        |
| `sent_at`       | timestamp of successful delivery (the proof it was sent)  |
| **UNIQUE(`appointment_id`, `lead_time_seconds`)** | the de-duplication backbone |

Lead times are fully configurable (`reminder.lead-times`), so any value can be
used — `T-24h` / `T-2h` are just the defaults.

---

## Why reminders can never be sent twice

This is guaranteed by **two layers**, each of which is enforced by PostgreSQL, not
by hopeful application logic:

**1. At most one reminder row per (appointment, lead time).**
The `UNIQUE (appointment_id, lead_time_seconds)` constraint means even a retried or
buggy "create appointment" cannot produce a second 24h reminder — the second insert
is rejected by the database. So there is a single, canonical row representing
"the 24h reminder for appointment X".

**2. That single row is delivered at most once.**
The dispatcher claims due reminders with:

```sql
SELECT ... FROM reminder
WHERE status = 'PENDING' AND send_at <= now()
ORDER BY send_at
FOR UPDATE SKIP LOCKED          -- row-locks each claimed row
```

and, in the *same transaction*, flips them `PENDING → SENDING`. Any other worker
(another thread, or a second app instance) polling at the same instant **skips the
locked rows** rather than double-claiming them. After the stub sender succeeds, the
row moves to `SENT`, which is **terminal** — the poll query filters on
`status = 'PENDING'`, so a `SENT` row is never selected again.

**It's provable from the data:** the `reminder` table *is* the audit log. Query it
and every reminder shows exactly one row with `status = SENT` and a single `sent_at`.
The test [`concurrentDispatchNeverSendsSameReminderTwice`](src/test/java/com/mykaarma/reminder/ReminderIdempotencyIntegrationTest.java)
hammers the dispatcher from 8 threads simultaneously and asserts that a counting
sender records **exactly one** send per reminder.

---

## How to run

### Prerequisites
- Java 17+ (`java -version`)
- Docker (for Postgres, and for the Testcontainers-based tests)

### 1. Start Postgres
```bash
docker compose up -d
```

### 2. Run the service
```bash
./mvnw spring-boot:run
```
The service starts on `http://localhost:8080`. Flyway creates the schema on first
boot. Health check: `curl http://localhost:8080/actuator/health`.

Configuration (all overridable via env vars) lives in
[`application.yml`](src/main/resources/application.yml):

| property                  | default | meaning                                  |
|---------------------------|---------|------------------------------------------|
| `reminder.lead-times`     | 24h, 2h | when reminders fire before appointment   |
| `reminder.poll-interval-ms` | 15000 | how often the dispatcher polls           |
| `reminder.batch-size`     | 500     | reminders claimed per poll cycle         |
| `reminder.max-attempts`   | 5       | delivery attempts before parking FAILED  |

> **Tip for the demo:** set a short lead time so reminders fire immediately, e.g.
> `REMINDER_LEAD_TIMES=PT1M,PT30S ./mvnw spring-boot:run`, then book an appointment
> a couple of minutes out.

---

## API

### Create an appointment
```bash
curl -i -X POST http://localhost:8080/appointments \
  -H 'Content-Type: application/json' \
  -d '{
        "dealershipId": "dealer-123",
        "customerName": "Jane Doe",
        "customerContact": "jane@example.com",
        "scheduledAt": "2026-10-05T15:00:00Z"
      }'
```
`201 Created` with a `Location` header and body:
```json
{
  "id": "0b7f...uuid",
  "dealershipId": "dealer-123",
  "customerName": "Jane Doe",
  "customerContact": "jane@example.com",
  "scheduledAt": "2026-10-05T15:00:00Z",
  "status": "BOOKED",
  "createdAt": "2026-09-29T09:30:00Z",
  "reminders": [
    { "id": 1, "label": "T-24h", "leadTimeSeconds": 86400, "sendAt": "2026-10-04T15:00:00Z", "status": "PENDING", "attempts": 0, "sentAt": null },
    { "id": 2, "label": "T-2h",  "leadTimeSeconds": 7200,  "sendAt": "2026-10-05T13:00:00Z", "status": "PENDING", "attempts": 0, "sentAt": null }
  ]
}
```

### Fetch an appointment (and its reminders)
```bash
curl http://localhost:8080/appointments/<id>    # e.g. /appointments/0394d2c8-a2ad-4112-...
```

Validation errors return `400` and not-found returns `404`, both as RFC 7807
`application/problem+json`.

---

## Demo walk-through

1. `docker compose up -d`
2. `REMINDER_LEAD_TIMES=PT2M,PT1M ./mvnw spring-boot:run`
3. Create an appointment ~2 minutes out (see curl above). Watch the log:
   `Created appointment ... with 2 reminders`.
4. Within a poll cycle the log shows:
   `NOTIFICATION SENT | reminderId=... | type=T-1m | ...`
   and `Dispatch cycle sent N reminders`.
5. Inspect the database:
   ```bash
   docker exec -it appointment-reminder-db \
     psql -U appointments -d appointments \
     -c "SELECT id, public_id, dealership_id, scheduled_at FROM appointment;" \
     -c "SELECT appointment_id, lead_time_seconds, status, attempts, sent_at FROM reminder ORDER BY appointment_id, lead_time_seconds;"
   ```
   Every reminder shows `status = SENT` with exactly one `sent_at` — the proof.

---

## Tests
```bash
docker compose up -d        # or just have Docker running for Testcontainers
./mvnw test
```

- **`ReminderPlannerTest`** — pure unit tests of reminder materialization and the
  "already-past reminder" corner case.
- **`ReminderIdempotencyIntegrationTest`** — the core guarantees against real
  Postgres: exactly two reminders per appointment, DB rejects duplicates, repeated
  dispatch sends once, **8-thread concurrent dispatch sends each reminder exactly
  once**, not-yet-due reminders aren't sent.
- **`ReminderRetryIntegrationTest`** — transient failures are retried and then sent
  exactly once; permanent failures are parked as `FAILED` after `max-attempts`.
- **`AppointmentApiIntegrationTest`** — HTTP contract: create, fetch, validation,
  not-found.

> **Note on Docker Engine 29+:** Testcontainers' bundled Docker client negotiates an
> old API version that Docker 29 rejects with HTTP 400. The build pins the client API
> version (`<api.version>1.44</api.version>` in the Surefire config) so tests work on
> current Docker Desktop. On macOS you may also need
> `export DOCKER_HOST=unix://$HOME/.docker/run/docker.sock`.

---

## Design decisions

- **Materialize reminders at booking time** instead of computing "who is due" by
  scanning appointments. This makes the unique constraint possible (the strongest
  possible de-dupe), turns dispatch into a simple indexed queue poll, and gives a
  durable per-reminder audit trail for free.
- **`FOR UPDATE SKIP LOCKED` polling** rather than a message broker. It's correct
  under horizontal scaling, needs no extra infrastructure, and is easy to reason
  about and demo. A broker (SQS/Kafka) is the natural next step at higher scale
  (see below).
- **Separate claim and delivery transactions.** Claiming a batch flips rows to
  `SENDING` in one transaction; each delivery then runs in its own
  `REQUIRES_NEW` transaction so one failed send doesn't roll back the whole batch.
- **At-least-once delivery, de-duped on `SENT`.** With a stub logger this is moot,
  but with a real provider a crash between "provider accepted" and "DB marked SENT"
  could re-send. `SENT` being terminal caps this at rare, controlled duplicates; a
  provider idempotency key would make it exactly-once end-to-end.
- **UUID `public_id`** exposed externally so we never leak sequential primary keys.
- **Everything in UTC**; formatting/localization is a presentation concern.
- **`NotificationSender` is an interface** — the logging stub is one implementation;
  a real SMS/email adapter drops in without touching dispatch logic.

---

## Corner cases & failure handling
- **Appointment booked inside the reminder window** (e.g. 30 min out, so the 24h/2h
  reminders are already overdue): the reminder is scheduled for *now* and fires on
  the next poll rather than being silently dropped. Covered by a test.
- **Past `scheduledAt`** is rejected at the API with `400`.
- **Transient send failure**: reminder returns to `PENDING`, retried up to
  `max-attempts`, then parked `FAILED` (never lost, never spammed).
- **Worker crash mid-send**: a reminder stranded in `SENDING` is returned to
  `PENDING` by a recovery sweep (`recoverStuckReminders`) after a threshold, and
  re-attempted. Because genuinely delivered reminders are `SENT` (not `SENDING`),
  recovery can never re-send a completed reminder.
- **Multiple app instances**: safe by construction thanks to `SKIP LOCKED`.
- **Malformed JSON / missing fields**: `400` with a problem-detail body.

---

## Scale
50,000 appointments/day × 2 reminders = **100k reminder rows/day** (~36M/year).
- Inserts are trivial for Postgres.
- The dispatch query is served by a **partial index** on `send_at WHERE status =
  'PENDING'`, which stays small because sent rows drop out of it.
- Throughput scales horizontally: run N instances; `SKIP LOCKED` partitions the work
  among them with no coordination and no double-sends.
- At much higher scale, partition/shard `reminder` by dealership or time, and move
  dispatch to a broker with a delayed-delivery queue.

---

## What I'd do with another week
- **Cancellation/reschedule endpoints**: `DELETE`/`PATCH` that cancel or move
  pending reminders (already modeled via `AppointmentStatus.CANCELLED`; dispatch
  would skip reminders whose appointment is cancelled).
- **Real notification adapters** (email/SMS) behind the existing interface, with a
  provider-side idempotency key for true end-to-end exactly-once.
- **Outbox → broker**: publish due reminders to SQS/Kafka with per-message delay for
  even smoother scaling and back-pressure.
- **Observability**: Micrometer metrics (reminders sent/failed/lag), tracing, and a
  dashboard; alert when `FAILED` count or dispatch lag grows.
- **Dead-letter handling & manual replay** for `FAILED` reminders.
- **Time-zone-aware scheduling** if reminders should respect the customer's local
  time / quiet hours.
- **Idempotent create** via a client `Idempotency-Key` header so retried POSTs return
  the original appointment.
- **Contract/API docs** via springdoc-openapi.

---

## Open questions from the brief
1. **What channel is the contact?** The brief says "customer contact" without
   specifying email vs SMS. I kept it channel-agnostic (`customerContact` free text)
   since the sender is a stub; a real system would validate per channel.
2. **Time zones.** Are the 24h/2h offsets in UTC or the dealership's local time?
   I assumed UTC. Quiet-hours rules (don't text at 3am) would change this.
3. **Cancellations / reschedules.** Not in scope of the brief, but real appointments
   change. I modeled the status and left the endpoints for the next iteration.
4. **Delivery semantics with a real provider.** Is rare duplicate delivery on crash
   acceptable (at-least-once), or is a provider idempotency key required for strict
   exactly-once? The stub makes this moot today; the design supports either.
5. **Retention.** How long do we keep `SENT`/`FAILED` reminder rows for auditing
   before archiving?
```
