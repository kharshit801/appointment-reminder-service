package com.mykaarma.reminder.web;

import com.mykaarma.reminder.domain.Appointment;
import com.mykaarma.reminder.service.AppointmentService;
import com.mykaarma.reminder.web.dto.AppointmentResponse;
import com.mykaarma.reminder.web.dto.CreateAppointmentRequest;
import com.mykaarma.reminder.web.dto.ReminderResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping
    public ResponseEntity<AppointmentResponse> create(@Valid @RequestBody CreateAppointmentRequest request,
                                                      UriComponentsBuilder uriBuilder) {
        Appointment appointment = appointmentService.createAppointment(request);
        List<ReminderResponse> reminders = appointmentService
                .getRemindersForAppointment(appointment.getPublicId())
                .stream()
                .map(ReminderResponse::from)
                .toList();

        URI location = uriBuilder.path("/appointments/{id}")
                .buildAndExpand(appointment.getPublicId())
                .toUri();
        return ResponseEntity.created(location)
                .body(AppointmentResponse.from(appointment, reminders));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AppointmentResponse> get(@PathVariable("id") UUID id) {
        Appointment appointment = appointmentService.getByPublicId(id);
        List<ReminderResponse> reminders = appointmentService.getRemindersForAppointment(id)
                .stream()
                .map(ReminderResponse::from)
                .toList();
        return ResponseEntity.status(HttpStatus.OK)
                .body(AppointmentResponse.from(appointment, reminders));
    }
}
