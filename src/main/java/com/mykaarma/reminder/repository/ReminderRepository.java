package com.mykaarma.reminder.repository;

import com.mykaarma.reminder.domain.Reminder;
import com.mykaarma.reminder.domain.ReminderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    List<Reminder> findByAppointmentIdOrderBySendAtAsc(Long appointmentId);

    /**
     * Claim a batch of due reminders for this worker.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is the concurrency backbone: each row is
     * row-locked as it is read, and any other worker (thread or separate app
     * instance) polling at the same time simply skips locked rows instead of
     * blocking or double-claiming. Combined with the state transition to SENDING
     * inside the same transaction, this guarantees a given reminder is handed to
     * exactly one worker.</p>
     */
    @Query("""
            SELECT r FROM Reminder r
            WHERE r.status = :status
              AND r.sendAt <= :now
            ORDER BY r.sendAt ASC
            """)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    List<Reminder> lockDueReminders(@Param("status") ReminderStatus status,
                                    @Param("now") Instant now,
                                    Limit limit);

    /**
     * Reminders stuck in SENDING whose last update is older than {@code threshold}.
     * Used by crash recovery to re-queue work a dead worker left behind.
     */
    @Query("""
            SELECT r FROM Reminder r
            WHERE r.status = com.mykaarma.reminder.domain.ReminderStatus.SENDING
              AND r.updatedAt < :threshold
            """)
    List<Reminder> findStuckSending(@Param("threshold") Instant threshold);
}
