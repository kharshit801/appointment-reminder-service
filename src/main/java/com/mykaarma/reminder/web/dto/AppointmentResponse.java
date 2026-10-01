package com.mykaarma.reminder.web.dto;

import com.mykaarma.reminder.domain.Appointment;
import com.mykaarma.reminder.domain.AppointmentStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AppointmentResponse(
        UUID id,
        String dealershipId,
        String customerName,
        String customerContact,
        Instant scheduledAt,
        AppointmentStatus status,
        Instant createdAt,
        List<ReminderResponse> reminders
) {
    public static AppointmentResponse from(Appointment appointment, List<ReminderResponse> reminders) {
        return new AppointmentResponse(
                appointment.getPublicId(),
                appointment.getDealershipId(),
                appointment.getCustomerName(),
                appointment.getCustomerContact(),
                appointment.getScheduledAt(),
                appointment.getStatus(),
                appointment.getCreatedAt(),
                reminders);
    }
}
