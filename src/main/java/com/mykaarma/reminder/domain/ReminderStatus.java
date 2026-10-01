package com.mykaarma.reminder.domain;

/**
 * Lifecycle of a single reminder.
 *
 * <pre>
 *   PENDING ──claim──▶ SENDING ──deliver ok──▶ SENT   (terminal)
 *      ▲                   │
 *      └──deliver fails────┘   (until attempts exhausted)
 *                          │
 *                          └──attempts exhausted──▶ FAILED (terminal)
 * </pre>
 *
 * SENT is terminal and is what makes "never sent twice" provable: once a row is
 * SENT it is never re-selected by the dispatcher.
 */
public enum ReminderStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED
}
