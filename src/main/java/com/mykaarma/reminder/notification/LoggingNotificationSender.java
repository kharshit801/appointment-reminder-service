package com.mykaarma.reminder.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stub sender required by the brief: it logs the payload instead of contacting a
 * real provider. The log line is intentionally structured so it is easy to grep
 * in the demo ("NOTIFICATION SENT ...").
 *
 * <p>Now includes the idempotency key in the log. A real provider implementation
 * (e.g., Twilio, SendGrid) would pass this key to the provider's API to ensure
 * retries don't cause duplicate sends.
 */
@Component
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(NotificationPayload payload, String idempotencyKey) {
        log.info("NOTIFICATION SENT | idempotencyKey={} | reminderId={} | type={} | appointment={} | dealership={} | to={} ({}) | appointmentAt={}",
                idempotencyKey,
                payload.reminderId(),
                payload.reminderLabel(),
                payload.appointmentPublicId(),
                payload.dealershipId(),
                payload.customerName(),
                payload.customerContact(),
                payload.appointmentScheduledAt());
    }
}
