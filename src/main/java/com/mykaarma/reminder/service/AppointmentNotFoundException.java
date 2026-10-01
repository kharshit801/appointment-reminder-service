package com.mykaarma.reminder.service;

import java.util.UUID;

public class AppointmentNotFoundException extends RuntimeException {

    public AppointmentNotFoundException(UUID publicId) {
        super("Appointment not found: " + publicId);
    }
}
