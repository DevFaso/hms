package com.example.hms.model.scheduling;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What an appointment IS, and how long it takes (P2 #11).
 *
 * <p>Duration belongs here rather than on the appointment, because it is a
 * property of the KIND of visit and not of whoever typed the end time. Today
 * every appointment carries its own start and end with nothing relating them to
 * what the visit actually is, which is why no report can say whether a clinic
 * is running to time.
 */
@Entity
@Table(
    name = "visit_types",
    schema = "clinical",
    uniqueConstraints = @UniqueConstraint(name = "uq_visit_type_code_hospital",
        columnNames = {"hospital_id", "code"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisitType extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_visit_type_hospital"))
    private Hospital hospital;

    /** Null for a hospital-wide visit type available to every department. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id",
        foreignKey = @ForeignKey(name = "fk_visit_type_department"))
    private Department department;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    /**
     * Whether a patient may book this themselves.
     *
     * <p>Defaults to false: self-scheduling a first oncology consultation is not
     * the same decision as self-scheduling a repeat blood-pressure check, and
     * opting in per visit type is the only way to tell them apart.
     */
    @Column(name = "patient_bookable", nullable = false)
    @Builder.Default
    private boolean patientBookable = false;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
