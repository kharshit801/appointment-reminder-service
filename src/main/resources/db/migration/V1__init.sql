-- Appointment: a vehicle service booking at a dealership.
CREATE TABLE appointment (
    id                BIGSERIAL PRIMARY KEY,
    -- Public, externally-safe identifier returned to API clients.
    public_id         UUID        NOT NULL UNIQUE,
    dealership_id     VARCHAR(64) NOT NULL,
    customer_name     VARCHAR(255) NOT NULL,
    -- Contact the reminder is delivered to (email or phone). Free-form on purpose;
    -- the stub sender does not care about the channel.
    customer_contact  VARCHAR(255) NOT NULL,
    -- When the service appointment is scheduled to happen (stored in UTC).
    scheduled_at      TIMESTAMPTZ NOT NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'BOOKED',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_appointment_dealership ON appointment (dealership_id);

-- Reminder: one materialized row per (appointment, reminder type).
-- Each row is both the work item for the dispatcher AND the audit record proving
-- whether/when the reminder was delivered.
CREATE TABLE reminder (
    id                BIGSERIAL PRIMARY KEY,
    appointment_id    BIGINT      NOT NULL REFERENCES appointment (id) ON DELETE CASCADE,
    -- e.g. T_MINUS_24H, T_MINUS_2H
    reminder_type     VARCHAR(32) NOT NULL,
    -- Absolute time this reminder becomes due (scheduled_at - lead time), UTC.
    send_at           TIMESTAMPTZ NOT NULL,
    -- PENDING -> SENDING -> SENT (terminal) | FAILED (terminal after max attempts)
    status            VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts          INT         NOT NULL DEFAULT 0,
    last_error        TEXT,
    sent_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- CORE IDEMPOTENCY GUARANTEE:
    -- at most one reminder of a given type can ever exist for an appointment.
    -- Duplicate materialization (e.g. from a retried create request) is rejected
    -- by the database, not by application logic.
    CONSTRAINT uq_reminder_appointment_type UNIQUE (appointment_id, reminder_type)
);

-- Dispatcher hot path: "give me PENDING reminders that are due now".
-- Partial index keeps the index small (only unsent rows) and the poll query fast.
CREATE INDEX idx_reminder_due
    ON reminder (send_at)
    WHERE status = 'PENDING';
