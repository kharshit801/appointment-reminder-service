# Design diagram

## Component / sequence overview

```mermaid
flowchart TB
    client([Client])

    subgraph svc[appointment-reminder-service]
        controller[AppointmentController]
        apptSvc[AppointmentService]
        planner[ReminderPlanner]
        scheduler[ReminderScheduler @Scheduled]
        dispatch[ReminderDispatchService]
        sender[NotificationSender - stub logs payload]
    end

    subgraph db[(PostgreSQL)]
        appt[[appointment]]
        rem[["reminder\nUNIQUE(appointment_id, lead_time_seconds)\npartial index on send_at WHERE status=PENDING"]]
    end

    client -- "POST /appointments" --> controller
    controller --> apptSvc
    apptSvc --> planner
    apptSvc -- "one transaction:\nappointment + 2 reminders" --> appt
    apptSvc --> rem
    client -- "GET /appointments/{id}" --> controller

    scheduler -- "every poll-interval" --> dispatch
    dispatch -- "claim due: FOR UPDATE SKIP LOCKED\nPENDING -> SENDING" --> rem
    dispatch -- "deliver (REQUIRES_NEW)" --> sender
    dispatch -- "mark SENT / record failure" --> rem
```

## Booking sequence

```mermaid
sequenceDiagram
    participant C as Client
    participant API as AppointmentController
    participant S as AppointmentService
    participant P as ReminderPlanner
    participant DB as PostgreSQL

    C->>API: POST /appointments {dealership, contact, scheduledAt}
    API->>S: createAppointment(request)
    activate S
    Note over S,DB: single transaction
    S->>DB: INSERT appointment
    S->>P: planFor(appointment, now)
    P-->>S: [T-24h reminder, T-2h reminder]
    S->>DB: INSERT 2 reminders (UNIQUE guards duplicates)
    deactivate S
    S-->>API: appointment + reminders
    API-->>C: 201 Created + Location
```

## Dispatch sequence (the exactly-once path)

```mermaid
sequenceDiagram
    participant SCH as ReminderScheduler
    participant D as ReminderDispatchService
    participant DB as PostgreSQL
    participant N as NotificationSender (stub)

    SCH->>D: dispatchDueReminders()
    activate D
    Note over D,DB: claim transaction
    D->>DB: SELECT ... WHERE status=PENDING AND send_at<=now<br/>FOR UPDATE SKIP LOCKED
    D->>DB: UPDATE claimed rows -> SENDING
    deactivate D
    loop each claimed reminder (own REQUIRES_NEW tx)
        D->>N: send(payload)
        alt success
            N-->>D: ok
            D->>DB: reminder -> SENT, set sent_at
        else failure
            N-->>D: throws
            D->>DB: attempts++, -> PENDING (retry) or FAILED (exhausted)
        end
    end
```

## Reminder state machine

```mermaid
stateDiagram-v2
    [*] --> PENDING: created at booking
    PENDING --> SENDING: claimed (SKIP LOCKED)
    SENDING --> SENT: delivered
    SENDING --> PENDING: delivery failed (attempts < max)
    SENDING --> FAILED: delivery failed (attempts == max)
    SENDING --> PENDING: crash recovery sweep
    SENT --> [*]
    FAILED --> [*]
```

`SENT` is terminal and is what makes "never sent twice" provable: the dispatch
query only ever selects `PENDING` rows, so a `SENT` reminder is never reconsidered.
