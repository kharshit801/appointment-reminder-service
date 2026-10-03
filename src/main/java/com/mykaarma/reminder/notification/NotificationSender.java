package com.mykaarma.reminder.notification;

/**
 * Abstraction over the outbound notification channel. The brief asks for a stub
 * that logs the payload rather than actually sending anything; keeping it behind
 * an interface means a real SMS/email provider can be dropped in later without
 * touching the dispatch logic.
 */
public interface NotificationSender {

    /**
     * Deliver the notification with an idempotency key.
     *
     * <p>The idempotency key prevents duplicate sends when integrating with
     * third-party providers (Twilio, SendGrid, etc.) that may not reliably
     * acknowledge delivery. If the provider times out and the app retries,
     * passing the same key ensures the provider deduplicates on their end.
     *
     * <p>Key format: {@code "reminder-{reminderId}"} - unique per reminder,
     * stable across retries.
     *
     * @param payload the notification content
     * @param idempotencyKey unique stable identifier for this notification
     * @throws RuntimeException if delivery fails (triggers retry in dispatcher)
     */
    void send(NotificationPayload payload, String idempotencyKey);
}
