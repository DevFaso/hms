package com.example.hms.model.scheduling;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A recurring clinic session (P2 #11): this clinician, this department, this
 * weekday, these hours, sliced this finely, between these dates.
 *
 * <p>The template is the intent; {@link AppointmentSlot} rows are what that
 * intent produces for concrete dates. Keeping them separate means editing next
 * month's clinic hours cannot silently move an appointment already booked for
 * next week.
 */
@Entity
@Table(name = "session_templates", schema = "clinical")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionTemplate extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_session_template_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_session_template_department"))
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_session_template_staff"))
    private Staff staff;

    /** Null for a general session that accepts any visit type. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_type_id",
        foreignKey = @ForeignKey(name = "fk_session_template_visit_type"))
    private VisitType visitType;

    /**
     * Stored as the ISO-8601 number (1 = Monday .. 7 = Sunday) so it matches
     * {@link DayOfWeek#getValue()} exactly. An ORDINAL mapping would silently
     * be off by one against every other system that speaks ISO weekdays.
     */
    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "slot_minutes", nullable = false)
    private int slotMinutes;

    /**
     * Slots generated per time position. Greater than one models a genuine
     * group or overbooked clinic rather than a double-booking accident.
     */
    @Column(name = "capacity_per_slot", nullable = false)
    @Builder.Default
    private int capacityPerSlot = 1;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Null means open-ended. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "notes", length = 500)
    private String notes;

    public DayOfWeek dayOfWeekEnum() {
        return DayOfWeek.of(dayOfWeek);
    }

    public void setDayOfWeekEnum(DayOfWeek day) {
        this.dayOfWeek = (short) day.getValue();
    }

    /** Whether this template should produce slots on the given date. */
    public boolean appliesOn(LocalDate date) {
        if (!active || date == null) {
            return false;
        }
        if (date.isBefore(effectiveFrom)) {
            return false;
        }
        if (effectiveTo != null && date.isAfter(effectiveTo)) {
            return false;
        }
        return date.getDayOfWeek().getValue() == dayOfWeek;
    }
}
