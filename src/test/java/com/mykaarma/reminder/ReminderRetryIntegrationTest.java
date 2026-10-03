package com.mykaarma.reminder;

import com.mykaarma.reminder.domain.Appointment;
import com.mykaarma.reminder.domain.Reminder;
import com.mykaarma.reminder.domain.ReminderStatus;
import com.mykaarma.reminder.notification.NotificationPayload;
import com.mykaarma.reminder.notification.NotificationSender;
import com.mykaarma.reminder.repository.AppointmentRepository;
import com.mykaarma.reminder.repository.ReminderRepository;
import com.mykaarma.reminder.service.AppointmentService;
import com.mykaarma.reminder.service.ReminderDispatchService;
import com.mykaarma.reminder.web.dto.CreateAppointmentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves failure handling: a reminder that fails to send is retried, and once it
 * eventually succeeds it is sent exactly once (never re-sent after SENT), and a
 * permanently failing reminder is parked as FAILED after max attempts.
 */
class ReminderRetryIntegrationTest extends AbstractPostgresIntegrationTest {

    /** 
     * Fails the first N send attempts per reminder id, then succeeds.
     * 
     * Accepts but doesn't validate idempotency key since this test focuses on
     * retry behavior, not key correctness.
     */
    static class FlakyNotificationSender implements NotificationSender {
        volatile int failFirst = 0;
        final Set<Long> permanentlyFailing = ConcurrentHashMap.newKeySet();
        final ConcurrentHashMap<Long, Integer> attemptsById = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long, Integer> successById = new ConcurrentHashMap<>();

        @Override
        public void send(NotificationPayload payload, String idempotencyKey) {
            long id = payload.reminderId();
            int attempt = attemptsById.merge(id, 1, Integer::sum);
            if (permanentlyFailing.contains(id) || attempt <= failFirst) {
                throw new RuntimeException("simulated transient failure attempt " + attempt);
            }
            successById.merge(id, 1, Integer::sum);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FlakyNotificationSender flakyNotificationSender() {
            return new FlakyNotificationSender();
        }
    }

    @Autowired
    AppointmentService appointmentService;
    @Autowired
    AppointmentRepository appointmentRepository;
    @Autowired
    ReminderRepository reminderRepository;
    @Autowired
    ReminderDispatchService dispatchService;
    @Autowired
    FlakyNotificationSender sender;

    @BeforeEach
    void clean() {
        reminderRepository.deleteAllInBatch();
        appointmentRepository.deleteAllInBatch();
        sender.attemptsById.clear();
        sender.successById.clear();
        sender.permanentlyFailing.clear();
        sender.failFirst = 0;
    }

    @Test
    void transientFailureIsRetriedThenSentExactlyOnce() {
        sender.failFirst = 2; // first two attempts per reminder fail
        Appointment appointment = createAppointment(Instant.now().plus(Duration.ofMinutes(30)));

        // Cycle 1: attempt #1 fails -> back to PENDING
        dispatchService.dispatchDueReminders();
        assertThat(reminderRepository.findAll()).allSatisfy(r ->
                assertThat(r.getStatus()).isEqualTo(ReminderStatus.PENDING));

        // Cycle 2: attempt #2 fails -> back to PENDING
        dispatchService.dispatchDueReminders();
        // Cycle 3: attempt #3 succeeds -> SENT
        dispatchService.dispatchDueReminders();

        List<Reminder> reminders = reminderRepository.findByAppointmentIdOrderBySendAtAsc(appointment.getId());
        assertThat(reminders).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(ReminderStatus.SENT);
            // attempts counts recorded failures only; two failures preceded the successful send.
            assertThat(r.getAttempts()).isEqualTo(2);
        });
        // Each reminder ultimately delivered exactly once.
        assertThat(sender.successById.values()).allSatisfy(count -> assertThat(count).isEqualTo(1));
    }

    @Test
    void permanentlyFailingReminderIsParkedAsFailedAfterMaxAttempts() {
        Appointment appointment = createAppointment(Instant.now().plus(Duration.ofMinutes(30)));
        // Mark all its reminders as permanently failing.
        reminderRepository.findByAppointmentIdOrderBySendAtAsc(appointment.getId())
                .forEach(r -> sender.permanentlyFailing.add(r.getId()));

        // Default maxAttempts is 5; run enough cycles to exhaust them.
        for (int i = 0; i < 10; i++) {
            dispatchService.dispatchDueReminders();
        }

        List<Reminder> reminders = reminderRepository.findByAppointmentIdOrderBySendAtAsc(appointment.getId());
        assertThat(reminders).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(ReminderStatus.FAILED);
            assertThat(r.getAttempts()).isEqualTo(5);
            assertThat(r.getSentAt()).isNull();
        });
        // Never delivered.
        assertThat(sender.successById).isEmpty();
    }

    private Appointment createAppointment(Instant scheduledAt) {
        return appointmentService.createAppointment(new CreateAppointmentRequest(
                "dealer-1", "Jane Doe", "jane@example.com", scheduledAt));
    }
}
