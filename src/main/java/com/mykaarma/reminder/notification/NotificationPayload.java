package com.mykaarma.reminder.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of what a reminder notification contains. Decoupled from the
 * JPA entities so the sender never touches persistence state.
 */
public record NotificationPayload(
        long reminderId,
        UUID appointmentPublicId,
        String dealershipId,
        String customerName,
        String customerContact,
        String reminderLabel,
        Instant appointmentScheduledAt
) {
}
