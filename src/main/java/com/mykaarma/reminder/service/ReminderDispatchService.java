package com.mykaarma.reminder.service;

import com.mykaarma.reminder.config.ReminderProperties;
import com.mykaarma.reminder.domain.Appointment;
import com.mykaarma.reminder.domain.Reminder;
import com.mykaarma.reminder.domain.ReminderStatus;
import com.mykaarma.reminder.notification.NotificationPayload;
import com.mykaarma.reminder.notification.NotificationSender;
import com.mykaarma.reminder.repository.AppointmentRepository;
import com.mykaarma.reminder.repository.ReminderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Claims and delivers due reminders.
 *
 * <p><b>Why a reminder can never be sent twice:</b></p>
 * <ol>
 *   <li>At most one row exists per (appointment, type) — DB unique constraint.</li>
 *   <li>{@link #claimDueReminders} selects PENDING due rows with
 *       {@code FOR UPDATE SKIP LOCKED} and flips them to SENDING inside one
 *       transaction. Any concurrent worker skips the locked rows, so a row is
 *       claimed by exactly one worker.</li>
 *   <li>Delivery marks the row SENT (terminal). A SENT row is never selected
 *       again because the poll query filters on status = PENDING.</li>
 * </ol>
 *
 * <p>Failure handling: if the send throws, the row is returned to PENDING (until
 * {@code maxAttempts}) for a later retry, or parked as FAILED. Because the claim
 * and the send are separated, a crash after claiming but before sending leaves a
 * row in SENDING; {@link #recoverStuckReminders} returns such rows to PENDING.
 * This is at-least-once delivery with a hard de-dupe on SENT — we never send a
 * reminder that already reached SENT.</p>
 */
@Service
public class ReminderDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ReminderDispatchService.class);

    private final ReminderRepository reminderRepository;
    private final AppointmentRepository appointmentRepository;
    private final NotificationSender notificationSender;
    private final ReminderProperties properties;
    private final ReminderDispatchService self;

    public ReminderDispatchService(ReminderRepository reminderRepository,
                                   AppointmentRepository appointmentRepository,
                                   NotificationSender notificationSender,
                                   ReminderProperties properties,
                                   @Lazy ReminderDispatchService self) {
        this.reminderRepository = reminderRepository;
        this.appointmentRepository = appointmentRepository;
        this.notificationSender = notificationSender;
        this.properties = properties;
        // Self-injected proxy so that internal calls to @Transactional methods go
        // through Spring's transaction interceptor rather than bypassing it via
        // plain "this" self-invocation.
        this.self = self;
    }

    /**
     * Run one dispatch cycle. Returns the number of reminders successfully sent.
     */
    public int dispatchDueReminders() {
        List<Reminder> claimed = self.claimDueReminders(Instant.now());
        if (claimed.isEmpty()) {
            return 0;
        }
        log.debug("Claimed {} due reminders", claimed.size());
        int sent = 0;
        for (Reminder reminder : claimed) {
            if (self.deliver(reminder.getId())) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * Atomically claim due reminders: lock PENDING due rows (skipping any already
     * locked by another worker) and transition them to SENDING in the same
     * transaction, so no other worker can pick them up.
     */
    @Transactional
    public List<Reminder> claimDueReminders(Instant now) {
        List<Reminder> due = reminderRepository.lockDueReminders(
                ReminderStatus.PENDING, now, Limit.of(properties.getBatchSize()));
        for (Reminder reminder : due) {
            reminder.markSending();
        }
        // flushed on tx commit; rows are now SENDING and invisible to other pollers
        return due;
    }

    /**
     * Deliver a single already-claimed (SENDING) reminder in its own transaction.
     * Isolating each reminder means one bad send does not roll back the successes
     * in the same batch.
     *
     * @return true if the reminder was delivered and marked SENT
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean deliver(Long reminderId) {
        Reminder reminder = reminderRepository.findById(reminderId).orElse(null);
        if (reminder == null || reminder.getStatus() != ReminderStatus.SENDING) {
            // Someone else handled it, or it changed state — nothing to do.
            return false;
        }
        Appointment appointment = appointmentRepository.findById(reminder.getAppointmentId()).orElse(null);
        if (appointment == null) {
            reminder.recordFailure("Appointment missing", properties.getMaxAttempts());
            return false;
        }

        try {
            String idempotencyKey = "reminder-" + reminder.getId();
            notificationSender.send(
                    new NotificationPayload(
                            reminder.getId(),
                            appointment.getPublicId(),
                            appointment.getDealershipId(),
                            appointment.getCustomerName(),
                            appointment.getCustomerContact(),
                            reminder.getLabel(),
                            appointment.getScheduledAt()),
                    idempotencyKey);
            reminder.markSent(Instant.now());
            return true;
        } catch (RuntimeException ex) {
            log.warn("Failed to deliver reminder {} (attempt {}): {}",
                    reminderId, reminder.getAttempts() + 1, ex.getMessage());
            reminder.recordFailure(ex.getMessage(), properties.getMaxAttempts());
            return false;
        }
    }

    /**
     * Return reminders stuck in SENDING (e.g. from a crashed worker) back to
     * PENDING so they can be retried. Safe because a genuinely sent reminder is
     * SENT, not SENDING.
     */
    @Transactional
    public int recoverStuckReminders(Instant olderThan) {
        List<Reminder> stuck = reminderRepository.findStuckSending(olderThan);
        for (Reminder reminder : stuck) {
            reminder.resetToPending(properties.getMaxAttempts());
        }
        if (!stuck.isEmpty()) {
            log.info("Recovered {} reminders stuck in SENDING back to PENDING", stuck.size());
        }
        return stuck.size();
    }
}
