package com.mykaarma.reminder.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Drives the dispatcher on a fixed interval. Kept deliberately thin — all the
 * transactional/idempotency logic lives in {@link ReminderDispatchService} so it
 * can be unit-tested without the scheduling machinery.
 */
@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    /** Reminders in SENDING for longer than this are considered stranded by a crash. */
    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(5);

    private final ReminderDispatchService dispatchService;

    public ReminderScheduler(ReminderDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelayString = "${reminder.poll-interval-ms}")
    public void poll() {
        try {
            int sent = dispatchService.dispatchDueReminders();
            if (sent > 0) {
                log.info("Dispatch cycle sent {} reminders", sent);
            }
        } catch (Exception ex) {
            // Never let a poll failure kill the scheduler thread.
            log.error("Dispatch cycle failed", ex);
        }
    }

    @Scheduled(fixedDelayString = "${reminder.poll-interval-ms}")
    public void recoverStuck() {
        try {
            dispatchService.recoverStuckReminders(Instant.now().minus(STUCK_THRESHOLD));
        } catch (Exception ex) {
            log.error("Stuck-reminder recovery failed", ex);
        }
    }
}
