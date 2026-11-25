package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * QA D3901 (r77): the patient's blood group as it is SHOWN, with what it rests on.
 *
 * <p>Two different facts share the words « groupe sanguin »:
 * <ul>
 *   <li>{@link #declared}: the group typed on the chart (registration, demographics edit by a doctor, nurse or
 *       midwife). Patient-reported or copied from a card, never verified: it stays editable, and it is always
 *       labelled « déclaré (non vérifié) ».</li>
 *   <li>{@link #confirmed}: the current ABO/Rh grouping performed by this hospital's laboratory (the blood bank's
 *       {@code PatientBloodGroup}, not superseded), with when and where. Written only by the laboratory; the
 *       declared value never overwrites it.</li>
 * </ul>
 * When both exist and differ, {@link #discrepancy} is true and every screen shows the confirmed group with the
 * warning. The confirmed group is the acting hospital's own, as the blood bank uses it: another hospital's grouping
 * is not shown (owner decision 2026-11-22, the group at another hospital is regrouped there). The allo-immunisation
 * flag, unlike the group, follows the patient to every hospital.
 *
 * <p>{@code BloodGroupDisplayGuardTest} keeps this object beside every response field that carries the declared
 * group, so no screen can show a typed group as if it were the laboratory's.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BloodGroupStatusDTO {

    /** The group typed on the chart, e.g. « A+ »; never verified. Null when none was typed. */
    private String declared;

    /** The laboratory's current grouping at the acting hospital, e.g. « B+ ». Null when none is on record. */
    private String confirmed;

    /** When the laboratory performed that grouping. */
    private LocalDateTime confirmedAt;

    /** The hospital whose laboratory performed it. */
    private String confirmedHospitalName;

    /** Both a declared and a confirmed group exist and they differ: the confirmed one is shown, with a warning. */
    private boolean discrepancy;

    /**
     * A positive antibody screen or an identified antibody is on record for the patient at any hospital. Null when
     * this read did not look (list reads); true or false on the reads of one chart (record, safety strip).
     */
    private Boolean alloImmunised;

    /** The antibody identified (« anti-E »); null while a positive screen is not yet identified. */
    private String antibodySpecificities;
}
