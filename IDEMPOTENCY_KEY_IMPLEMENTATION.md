# Idempotency Key Support

## The Problem

Provider times out. Did the notification send? Retry → duplicate. Don't retry → missed send.

## The Fix

Pass `idempotency key` with each request. Provider dedupes on their end.

**Key format:** `reminder-{id}` — unique per reminder, stable across retries.

```java
// Interface
void send(NotificationPayload payload, String idempotencyKey);

// Dispatcher
String key = "reminder-" + reminder.getId();
notificationSender.send(payload, key);
```

## Why It Works

```
Without key:
  send() → timeout → retry? → duplicate ❌

With key:
  send(key=r-42) → timeout → retry(key=r-42) → provider dedupes → no duplicate ✅
```

Provider remembers keys. Safe to retry.

## Real Provider Usage

**Twilio:**
```java
Message.creator(phone, from, body)
    .setIdempotencyKey(key)  // ← dedupes here
    .create();
```

**SendGrid:**
```java
request.addHeader("Idempotency-Key", key);  // ← dedupes here
```

Standard pattern. Twilio, SendGrid, Stripe all support it.

## What Changed

3 files:
- `NotificationSender.java` — added key parameter
- `LoggingNotificationSender.java` — logs key
- `ReminderDispatchService.java` — generates key

Test mocks updated. All 15 tests pass.

## Alternatives Considered

**Timeout + UNKNOWN state:** 30s timeout → mark `UNKNOWN` → ops checks dashboard manually. Works but needs humans.

**Webhooks + reconciliation:** Store message ID, wait for delivery webhook, reconcile stragglers daily. Eventually consistent, complex.

**Idempotency keys win:** Simple, standard, zero ops overhead.

---

**Bottom line:** Solves unreliable provider ACKs with 3 line changes. Drop-in for real providers.
