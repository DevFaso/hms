package com.example.hms.repository.scheduling;

import com.example.hms.enums.SlotStatus;
import com.example.hms.model.scheduling.AppointmentSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface AppointmentSlotRepository extends JpaRepository<AppointmentSlot, UUID> {

    boolean existsByStaff_IdAndStartAt(UUID staffId, LocalDateTime startAt);

    List<AppointmentSlot> findBySessionTemplate_IdAndSlotDateBetween(
        UUID sessionTemplateId, LocalDate from, LocalDate to);

    /**
     * The open-slot search — the question this whole model exists to answer:
     * "who is free, in this department, for this kind of visit, over this
     * window?"
     *
     * <p>An expired HELD row counts as available without waiting for the reclaim
     * sweep. A patient who abandoned a booking twenty minutes ago must not keep
     * a slot out of circulation until a scheduler happens to run.
     */
    @Query("SELECT s FROM AppointmentSlot s "
        + "WHERE s.hospital.id = :hospitalId "
        + "AND (:departmentId IS NULL OR s.department.id = :departmentId) "
        + "AND (:staffId IS NULL OR s.staff.id = :staffId) "
        + "AND (:visitTypeId IS NULL OR s.visitType.id = :visitTypeId) "
        + "AND s.slotDate BETWEEN :from AND :to "
        + "AND s.startAt > :notBefore "
        + "AND (s.status = com.example.hms.enums.SlotStatus.OPEN "
        + "     OR (s.status = com.example.hms.enums.SlotStatus.HELD AND s.heldUntil < :now)) "
        + "ORDER BY s.startAt ASC")
    List<AppointmentSlot> searchOpen(@Param("hospitalId") UUID hospitalId,
                                     @Param("departmentId") UUID departmentId,
                                     @Param("staffId") UUID staffId,
                                     @Param("visitTypeId") UUID visitTypeId,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to,
                                     @Param("notBefore") LocalDateTime notBefore,
                                     @Param("now") LocalDateTime now,
                                     org.springframework.data.domain.Pageable pageable);

    /** Holds whose window has passed, for the reclaim sweep. */
    List<AppointmentSlot> findByStatusAndHeldUntilBefore(SlotStatus status, LocalDateTime cutoff);

    /** FHIR Slot search (Tier 2 item 43): the inventory over a date window, tenant-scoped. */
    org.springframework.data.domain.Page<AppointmentSlot>
        findByHospital_IdAndSlotDateBetweenOrderByStartAtAsc(
            UUID hospitalId, java.time.LocalDate from, java.time.LocalDate to,
            org.springframework.data.domain.Pageable pageable);

    /** The slot an appointment was booked from, for free-on-cancel (P3 #22). */
    java.util.Optional<AppointmentSlot> findByAppointment_Id(UUID appointmentId);
}
