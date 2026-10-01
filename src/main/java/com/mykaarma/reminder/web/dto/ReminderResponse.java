package com.mykaarma.reminder.web.dto;

import com.mykaarma.reminder.domain.Reminder;
import com.mykaarma.reminder.domain.ReminderStatus;

import java.time.Instant;

public record ReminderResponse(
        Long id,
        String label,
        long leadTimeSeconds,
        Instant sendAt,
        ReminderStatus status,
        int attempts,
        Instant sentAt
) {
    public static ReminderResponse from(Reminder reminder) {
        return new ReminderResponse(
                reminder.getId(),
                reminder.getLabel(),
                reminder.getLeadTimeSeconds(),
                reminder.getSendAt(),
                reminder.getStatus(),
                reminder.getAttempts(),
                reminder.getSentAt());
    }
}
