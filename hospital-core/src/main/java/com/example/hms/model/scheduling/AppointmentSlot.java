package com.example.hms.model.scheduling;

import com.example.hms.enums.SlotStatus;
import com.example.hms.model.Appointment;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One concrete, bookable unit of a clinician's time (P2 #11).
 *
 * <p>Materialised rather than computed from "template minus existing
 * appointments", because a slot has STATE that a derived view cannot carry: it
 * can be held while a patient finishes booking, or blocked for a meeting. It
 * also turns double-booking from a race into a constraint — the unique index on
 * (staff_id, start_at) is what actually stops two patients being given the same
 * moment, rather than whichever service remembered to check.
 */
@Entity
@Table(
    name = "appointment_slots",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_slot_appointment", columnList = "appointment_id"),
        @Index(name = "idx_slot_held_until", columnList = "held_until")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentSlot extends BaseEntity {

    /** Null for a one-off slot opened by hand rather than generated. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_template_id",
        foreignKey = @ForeignKey(name = "fk_slot_template"))
    private SessionTemplate sessionTemplate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_slot_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_slot_department"))
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_slot_staff"))
    private Staff staff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_type_id",
        foreignKey = @ForeignKey(name = "fk_slot_visit_type"))
    private VisitType visitType;

    /** Denormalised from start_at so date-range searches never compute a cast. */
    @Column(name = "slot_date", nullable = false)
    private LocalDate slotDate;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private SlotStatus status = SlotStatus.OPEN;

    /**
     * ON DELETE SET NULL in the schema: cancelling an appointment must free the
     * slot, not destroy it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id",
        foreignKey = @ForeignKey(name = "fk_slot_appointment"))
    private Appointment appointment;

    @Column(name = "held_until")
    private LocalDateTime heldUntil;

    @Column(name = "held_by_user_id")
    private UUID heldByUserId;

    @Column(name = "blocked_reason", length = 255)
    private String blockedReason;

    /** Optimistic lock (V128): hold() and book() are check-then-act — two
     *  concurrent callers both pass isOfferable and the second silently
     *  wins without this. The GeneralReferral @Version precedent. */
    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private long version = 0L;

    /**
     * Whether this slot can be offered right now.
     *
     * <p>An expired hold counts as offerable without waiting for the reclaim
     * sweep: a patient who abandoned a booking twenty minutes ago must not keep
     * a slot out of circulation until a scheduler happens to run.
     */
    public boolean isOfferable(LocalDateTime now) {
        if (status == SlotStatus.OPEN) {
            return true;
        }
        return status == SlotStatus.HELD && heldUntil != null && heldUntil.isBefore(now);
    }
}
