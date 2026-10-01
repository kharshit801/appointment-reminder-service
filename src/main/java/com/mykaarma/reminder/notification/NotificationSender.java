package com.mykaarma.reminder.notification;

/**
 * Abstraction over the outbound notification channel. The brief asks for a stub
 * that logs the payload rather than actually sending anything; keeping it behind
 * an interface means a real SMS/email provider can be dropped in later without
 * touching the dispatch logic.
 */
public interface NotificationSender {

    /**
     * Deliver the notification. Implementations should throw if delivery fails so
     * the dispatcher can record the failure and schedule a retry.
     */
    void send(NotificationPayload payload);
}
