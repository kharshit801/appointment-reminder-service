package com.mykaarma.reminder.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Duration;
import java.time.Instant;

/**
 * A single scheduled reminder for an appointment.
 *
 * <p>A reminder is identified by its <em>lead time</em> — how long before the
 * appointment it fires (e.g. 24h, 2h, or any configured value). There is at most
 * one row per (appointment, lead time) thanks to a unique constraint, and the row
 * doubles as the durable audit record of delivery.</p>
 */
@Entity
@Table(name = "reminder")
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appointment_id", nullable = false)
    private Long appointmentId;

    /**
     * Lead time in seconds (appointment time − send time). This is what makes a
     * reminder unique for an appointment, so lead times are fully configurable
     * rather than limited to a fixed set of enum values.
     */
    @Column(name = "lead_time_seconds", nullable = false)
    private long leadTimeSeconds;

    @Column(name = "send_at", nullable = false)
    private Instant sendAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReminderStatus status = ReminderStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Reminder() {
        // for JPA
    }

    public Reminder(Long appointmentId, Duration leadTime, Instant sendAt) {
        this.appointmentId = appointmentId;
        this.leadTimeSeconds = leadTime.getSeconds();
        this.sendAt = sendAt;
        this.status = ReminderStatus.PENDING;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void markSending() {
        this.status = ReminderStatus.SENDING;
    }

    /**
     * Recovery path for a reminder stranded in SENDING by a crashed worker.
     * Counts as a failed attempt so a repeatedly-crashing delivery cannot loop
     * forever; parks as FAILED once attempts are exhausted.
     */
    public void resetToPending(int maxAttempts) {
        this.attempts += 1;
        this.lastError = "Recovered from stuck SENDING state";
        this.status = this.attempts >= maxAttempts ? ReminderStatus.FAILED : ReminderStatus.PENDING;
    }

    public void markSent(Instant when) {
        this.status = ReminderStatus.SENT;
        this.sentAt = when;
        this.lastError = null;
    }

    public void recordFailure(String error, int maxAttempts) {
        this.attempts += 1;
        this.lastError = error;
        // Return to PENDING for retry unless we've exhausted attempts, in which
        // case park the row as FAILED so it stops being polled.
        this.status = this.attempts >= maxAttempts ? ReminderStatus.FAILED : ReminderStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public Long getAppointmentId() {
        return appointmentId;
    }

    public long getLeadTimeSeconds() {
        return leadTimeSeconds;
    }

    @Transient
    public Duration getLeadTime() {
        return Duration.ofSeconds(leadTimeSeconds);
    }

    /**
     * Human-readable label for the lead time, e.g. {@code T-24h}, {@code T-2h},
     * {@code T-30m}, {@code T-45s}. Used in API responses and notification logs.
     */
    @Transient
    public String getLabel() {
        return labelFor(Duration.ofSeconds(leadTimeSeconds));
    }

    /**
     * Format a lead time as a compact label using the largest whole unit that
     * divides it evenly (hours, else minutes, else seconds).
     */
    public static String labelFor(Duration leadTime) {
        long seconds = leadTime.getSeconds();
        if (seconds % 3600 == 0) {
            return "T-" + (seconds / 3600) + "h";
        }
        if (seconds % 60 == 0) {
            return "T-" + (seconds / 60) + "m";
        }
        return "T-" + seconds + "s";
    }

    public Instant getSendAt() {
        return sendAt;
    }

    public ReminderStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
