package com.mykaarma.reminder.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stub sender required by the brief: it logs the payload instead of contacting a
 * real provider. The log line is intentionally structured so it is easy to grep
 * in the demo ("NOTIFICATION SENT ...").
 */
@Component
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(NotificationPayload payload) {
        log.info("NOTIFICATION SENT | reminderId={} | type={} | appointment={} | dealership={} | to={} ({}) | appointmentAt={}",
                payload.reminderId(),
                payload.reminderLabel(),
                payload.appointmentPublicId(),
                payload.dealershipId(),
                payload.customerName(),
                payload.customerContact(),
                payload.appointmentScheduledAt());
    }
}
