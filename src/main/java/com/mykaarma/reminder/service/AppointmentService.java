package com.mykaarma.reminder.service;

import com.mykaarma.reminder.domain.Appointment;
import com.mykaarma.reminder.domain.Reminder;
import com.mykaarma.reminder.repository.AppointmentRepository;
import com.mykaarma.reminder.repository.ReminderRepository;
import com.mykaarma.reminder.web.dto.CreateAppointmentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    private final AppointmentRepository appointmentRepository;
    private final ReminderRepository reminderRepository;
    private final ReminderPlanner reminderPlanner;

    public AppointmentService(AppointmentRepository appointmentRepository,
                              ReminderRepository reminderRepository,
                              ReminderPlanner reminderPlanner) {
        this.appointmentRepository = appointmentRepository;
        this.reminderRepository = reminderRepository;
        this.reminderPlanner = reminderPlanner;
    }

    /**
     * Create an appointment and its reminders in a single transaction. Either both
     * the appointment and its reminder rows are persisted, or neither is — there is
     * never an appointment without its reminders. The unique constraint on
     * (appointment_id, lead_time_seconds) means even a buggy double-materialization is
     * rejected by the database rather than producing duplicate reminders.
     */
    @Transactional
    public Appointment createAppointment(CreateAppointmentRequest request) {
        Instant now = Instant.now();
        Appointment appointment = new Appointment(
                request.dealershipId(),
                request.customerName(),
                request.customerContact(),
                request.scheduledAt());
        appointment = appointmentRepository.save(appointment);

        List<Reminder> reminders = reminderPlanner.planFor(appointment, now);
        reminderRepository.saveAll(reminders);

        log.info("Created appointment {} for dealership {} scheduled at {} with {} reminders",
                appointment.getPublicId(), appointment.getDealershipId(),
                appointment.getScheduledAt(), reminders.size());
        return appointment;
    }

    @Transactional(readOnly = true)
    public Appointment getByPublicId(UUID publicId) {
        return appointmentRepository.findByPublicId(publicId)
                .orElseThrow(() -> new AppointmentNotFoundException(publicId));
    }

    @Transactional(readOnly = true)
    public List<Reminder> getRemindersForAppointment(UUID publicId) {
        Appointment appointment = getByPublicId(publicId);
        return reminderRepository.findByAppointmentIdOrderBySendAtAsc(appointment.getId());
    }
}
