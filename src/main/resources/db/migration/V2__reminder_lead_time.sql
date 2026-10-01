-- Make reminder lead times fully configurable.
--
-- Previously a reminder was identified by a fixed enum (reminder_type: T_MINUS_24H
-- / T_MINUS_2H). This replaces that with lead_time_seconds so any configured lead
-- time works, while preserving the core idempotency guarantee: at most one reminder
-- per (appointment, lead time).

-- Drop the old type-based uniqueness constraint.
ALTER TABLE reminder DROP CONSTRAINT IF EXISTS uq_reminder_appointment_type;

-- New identity: lead time in seconds (appointment time - send time).
ALTER TABLE reminder ADD COLUMN lead_time_seconds BIGINT;

-- Backfill from the old enum so existing rows keep their real lead time
-- instead of collapsing to a single value that would violate the new
-- unique constraint.
UPDATE reminder
   SET lead_time_seconds = CASE reminder_type
                             WHEN 'T_MINUS_24H' THEN 86400
                             WHEN 'T_MINUS_2H'  THEN 7200
                           END;

-- Any other/unknown reminder_type: derive the lead time from the schedule.
UPDATE reminder r
   SET lead_time_seconds = EXTRACT(EPOCH FROM (a.scheduled_at - r.send_at))::BIGINT
  FROM appointment a
 WHERE a.id = r.appointment_id
   AND r.lead_time_seconds IS NULL;

ALTER TABLE reminder ALTER COLUMN lead_time_seconds SET NOT NULL;

-- Drop the old type-based column.
ALTER TABLE reminder DROP COLUMN reminder_type;

-- CORE IDEMPOTENCY GUARANTEE (unchanged in spirit):
-- at most one reminder of a given lead time can ever exist for an appointment.
ALTER TABLE reminder
    ADD CONSTRAINT uq_reminder_appointment_leadtime UNIQUE (appointment_id, lead_time_seconds);
