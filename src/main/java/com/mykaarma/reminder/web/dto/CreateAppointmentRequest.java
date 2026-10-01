package com.mykaarma.reminder.web.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Request body for creating an appointment.
 *
 * @param dealershipId     which dealership the appointment belongs to
 * @param customerName     display name of the customer
 * @param customerContact  email or phone the reminder is delivered to
 * @param scheduledAt      when the service appointment happens (ISO-8601 UTC, e.g. 2026-10-01T15:00:00Z)
 */
public record CreateAppointmentRequest(
        @NotBlank(message = "dealershipId is required")
        String dealershipId,

        @NotBlank(message = "customerName is required")
        String customerName,

        @NotBlank(message = "customerContact is required")
        String customerContact,

        @NotNull(message = "scheduledAt is required")
        @Future(message = "scheduledAt must be in the future")
        Instant scheduledAt
) {
}
