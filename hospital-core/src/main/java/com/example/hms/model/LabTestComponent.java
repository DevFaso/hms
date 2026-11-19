package com.example.hms.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * r63 (NFS panels): one analyte of a panel test, persisted as JSON on
 * {@link LabTestDefinition#getComponents()}. A full blood count is one order,
 * one specimen and one release, but a dozen values (Hb, WBC, platelets, the
 * differential...), each with its own unit and its own reference ranges,
 * critical limits included. The ranges use the same value object, and are
 * graded by the same rules, as a single-value test's.
 *
 * <p>Like the test's own ranges, components are part of what QA and the
 * director approve, so they lock with them (LabTestDefinitionServiceImpl).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LabTestComponent implements Serializable {
    /** Short code, unique within the panel ("HB", "WBC", "PLT"). */
    private String code;
    /** Display name ("Hémoglobine"). */
    private String name;
    /** The unit every value of this component is entered in. */
    private String unit;
    @Builder.Default
    private List<LabTestReferenceRange> referenceRanges = new ArrayList<>();
}
