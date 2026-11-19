package com.example.hms.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * r63 (NFS panels): the value entered for one {@link LabTestComponent} of a
 * panel, persisted as JSON on {@link LabResult#getComponents()}. The name and
 * unit are copied from the definition at entry so the stored result keeps
 * reading the same if the panel is later versioned.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LabResultComponent implements Serializable {
    private String code;
    private String name;
    private String value;
    private String unit;
}
