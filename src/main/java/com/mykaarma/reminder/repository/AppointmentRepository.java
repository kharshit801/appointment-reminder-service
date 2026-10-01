package com.mykaarma.reminder.repository;

import com.mykaarma.reminder.domain.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    Optional<Appointment> findByPublicId(UUID publicId);
}
