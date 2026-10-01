package com.mykaarma.reminder.service;

import com.mykaarma.reminder.config.ReminderProperties;
import com.mykaarma.reminder.domain.Appointment;
import com.mykaarma.reminder.domain.Reminder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns an appointment into the set of reminder rows that should exist for it,
 * one per configured lead time.
 *
 * <p>Corner case handled here: if a reminder's send time is already in the past
 * at creation (e.g. someone books an appointment 30 minutes from now, so the 24h
 * and 2h reminders are both overdue), we still create the row with a send time of
 * "now". The dispatcher will then fire it on the next poll. We deliberately do not
 * silently drop it — a slightly-late reminder is more useful than none, and the
 * behaviour is explicit and testable.</p>
 */
@Component
public class ReminderPlanner {

    private static final Logger log = LoggerFactory.getLogger(ReminderPlanner.class);

    private final ReminderProperties properties;

    public ReminderPlanner(ReminderProperties properties) {
        this.properties = properties;
    }

    public List<Reminder> planFor(Appointment appointment, Instant now) {
        List<Reminder> reminders = new ArrayList<>();
        // De-duplicate lead times so a misconfiguration (same value twice) can't
        // violate the (appointment, lead time) unique constraint at insert time.
        Set<Duration> leadTimes = new LinkedHashSet<>(properties.getLeadTimes());
        for (Duration leadTime : leadTimes) {
            Instant sendAt = appointment.getScheduledAt().minus(leadTime);
            if (sendAt.isBefore(now)) {
                // Reminder window already passed at booking time — fire ASAP instead of dropping.
                log.info("Reminder {} for appointment {} is already due at creation; scheduling immediately",
                        Reminder.labelFor(leadTime), appointment.getPublicId());
                sendAt = now;
            }
            reminders.add(new Reminder(appointment.getId(), leadTime, sendAt));
        }
        return reminders;
    }
}
