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
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The heart of the assignment: proving a customer never receives the same
 * reminder twice.
 */
class ReminderIdempotencyIntegrationTest extends AbstractPostgresIntegrationTest {

    /**
     * A NotificationSender that counts how many times each reminder id is sent.
     * If any id is ever counted twice, the "never twice" guarantee is broken.
     * 
     * Validates idempotency key format (reminder-{id}) to ensure keys are stable.
     */
    static class CountingNotificationSender implements NotificationSender {
        final Map<Long, AtomicInteger> sendCounts = new ConcurrentHashMap<>();
        final Map<Long, String> observedKeys = new ConcurrentHashMap<>();

        @Override
        public void send(NotificationPayload payload, String idempotencyKey) {
            long reminderId = payload.reminderId();
            
            // Verify idempotency key format
            String expectedKey = "reminder-" + reminderId;
            if (!expectedKey.equals(idempotencyKey)) {
                throw new AssertionError("Idempotency key mismatch: expected " + expectedKey + " but got " + idempotencyKey);
            }
            
            // Verify key is stable across retries
            String previousKey = observedKeys.putIfAbsent(reminderId, idempotencyKey);
            if (previousKey != null && !previousKey.equals(idempotencyKey)) {
                throw new AssertionError("Idempotency key changed for reminder " + reminderId + ": was " + previousKey + ", now " + idempotencyKey);
            }
            
            sendCounts.computeIfAbsent(reminderId, k -> new AtomicInteger()).incrementAndGet();
        }

        int totalSends() {
            return sendCounts.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        boolean anySentMoreThanOnce() {
            return sendCounts.values().stream().anyMatch(c -> c.get() > 1);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        CountingNotificationSender countingNotificationSender() {
            return new CountingNotificationSender();
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
    CountingNotificationSender sender;

    @BeforeEach
    void clean() {
        reminderRepository.deleteAllInBatch();
        appointmentRepository.deleteAllInBatch();
        sender.sendCounts.clear();
        sender.observedKeys.clear();
    }

    @Test
    void creatingAppointmentMaterializesExactlyTwoPendingReminders() {
        Appointment appointment = createAppointment(Instant.now().plus(Duration.ofDays(3)));

        List<Reminder> reminders = reminderRepository.findByAppointmentIdOrderBySendAtAsc(appointment.getId());
        assertThat(reminders).hasSize(2);
        assertThat(reminders).extracting(Reminder::getStatus)
                .containsOnly(ReminderStatus.PENDING);
        assertThat(reminders).extracting(Reminder::getLabel)
                .containsExactlyInAnyOrder("T-24h", "T-2h");
    }

    @Test
    void databaseRejectsDuplicateReminderOfSameLeadTimeForAppointment() {
        Appointment appointment = createAppointment(Instant.now().plus(Duration.ofDays(3)));

        // Attempt to insert a second 24h reminder for the same appointment.
        Reminder duplicate = new Reminder(appointment.getId(), Duration.ofHours(24), Instant.now());
        assertThatThrownBy(() -> reminderRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void repeatedDispatchSendsEachDueReminderExactlyOnce() {
        // Appointment in the past-ish window so both reminders are already due.
        createAppointment(Instant.now().plus(Duration.ofMinutes(30)));

        // Run the dispatcher several times; only the first should actually send.
        dispatchService.dispatchDueReminders();
        dispatchService.dispatchDueReminders();
        dispatchService.dispatchDueReminders();

        assertThat(sender.totalSends()).isEqualTo(2);
        assertThat(sender.anySentMoreThanOnce()).isFalse();
        assertThat(reminderRepository.findAll()).extracting(Reminder::getStatus)
                .containsOnly(ReminderStatus.SENT);
    }

    @Test
    void concurrentDispatchNeverSendsSameReminderTwice() throws InterruptedException {
        // Many due reminders across many appointments.
        int appointments = 40;
        for (int i = 0; i < appointments; i++) {
            createAppointment(Instant.now().plus(Duration.ofMinutes(30)));
        }
        long expectedReminders = reminderRepository.count();
        assertThat(expectedReminders).isEqualTo(appointments * 2L);

        // Hammer the dispatcher from multiple threads simultaneously, simulating
        // multiple app instances / worker threads polling at once.
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                for (int c = 0; c < 5; c++) {
                    dispatchService.dispatchDueReminders();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(sender.anySentMoreThanOnce())
                .as("no reminder should ever be sent more than once")
                .isFalse();
        assertThat(sender.totalSends()).isEqualTo(expectedReminders);
        assertThat(reminderRepository.findAll()).extracting(Reminder::getStatus)
                .containsOnly(ReminderStatus.SENT);
    }

    @Test
    void remindersNotYetDueAreNotSent() {
        // Appointment far in the future: neither reminder is due yet.
        createAppointment(Instant.now().plus(Duration.ofDays(10)));

        int sent = dispatchService.dispatchDueReminders();

        assertThat(sent).isZero();
        assertThat(sender.totalSends()).isZero();
        assertThat(reminderRepository.findAll()).extracting(Reminder::getStatus)
                .containsOnly(ReminderStatus.PENDING);
    }

    private Appointment createAppointment(Instant scheduledAt) {
        return appointmentService.createAppointment(new CreateAppointmentRequest(
                "dealer-1", "Jane Doe", "jane@example.com", scheduledAt));
    }
}
