package com.mykaarma.reminder;

import com.mykaarma.reminder.config.ReminderProperties;
import com.mykaarma.reminder.domain.Appointment;
import com.mykaarma.reminder.domain.Reminder;
import com.mykaarma.reminder.service.ReminderPlanner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for reminder materialization — no Spring, no database.
 */
class ReminderPlannerTest {

    private final ReminderProperties properties = new ReminderProperties();
    private final ReminderPlanner planner = new ReminderPlanner(properties);

    @Test
    void createsOneReminderPerConfiguredLeadTime() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        Appointment appointment = appointmentScheduledAt(now.plus(Duration.ofDays(7)));

        List<Reminder> reminders = planner.planFor(appointment, now);

        assertThat(reminders).hasSize(2);
        assertThat(reminders).extracting(Reminder::getLabel)
                .containsExactlyInAnyOrder("T-24h", "T-2h");
    }

    @Test
    void computesSendTimesFromLeadTimes() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        Instant scheduledAt = Instant.parse("2026-10-08T10:00:00Z");
        Appointment appointment = appointmentScheduledAt(scheduledAt);

        List<Reminder> reminders = planner.planFor(appointment, now);

        Reminder t24 = reminders.stream().filter(r -> r.getLeadTime().equals(Duration.ofHours(24))).findFirst().orElseThrow();
        Reminder t2 = reminders.stream().filter(r -> r.getLeadTime().equals(Duration.ofHours(2))).findFirst().orElseThrow();
        assertThat(t24.getSendAt()).isEqualTo(Instant.parse("2026-10-07T10:00:00Z"));
        assertThat(t2.getSendAt()).isEqualTo(Instant.parse("2026-10-08T08:00:00Z"));
    }

    @Test
    void clampsAlreadyPastReminderToNowInsteadOfDropping() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        // Appointment only 30 minutes away: both 24h and 2h reminders are already overdue.
        Appointment appointment = appointmentScheduledAt(now.plus(Duration.ofMinutes(30)));

        List<Reminder> reminders = planner.planFor(appointment, now);

        assertThat(reminders).hasSize(2);
        assertThat(reminders).allSatisfy(r -> assertThat(r.getSendAt()).isEqualTo(now));
    }

    private Appointment appointmentScheduledAt(Instant scheduledAt) {
        return new Appointment("dealer-1", "Jane Doe", "jane@example.com", scheduledAt);
    }
}
