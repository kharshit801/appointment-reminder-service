package com.mykaarma.reminder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Externalized reminder configuration. Lead times drive which reminder rows are
 * materialized when an appointment is created.
 */
@ConfigurationProperties(prefix = "reminder")
public class ReminderProperties {

    /** Lead times before the appointment at which reminders fire (e.g. PT24H, PT2H). */
    private List<Duration> leadTimes = List.of(Duration.ofHours(24), Duration.ofHours(2));

    /** How often the dispatcher polls for due reminders, in milliseconds. */
    private long pollIntervalMs = 15000;

    /** Maximum reminders claimed per poll cycle. */
    private int batchSize = 500;

    /** Maximum delivery attempts before a reminder is parked as FAILED. */
    private int maxAttempts = 5;

    public List<Duration> getLeadTimes() {
        return leadTimes;
    }

    public void setLeadTimes(List<Duration> leadTimes) {
        this.leadTimes = leadTimes;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
