package com.example.hms.model;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * Composite identifier for {@link ImagingReportAttachment} rows (report + position).
 */
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
public class ImagingReportAttachmentId implements Serializable {

    private UUID report;
    private Integer position;
}
