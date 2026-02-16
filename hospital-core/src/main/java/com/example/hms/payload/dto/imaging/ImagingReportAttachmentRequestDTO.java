package com.example.hms.payload.dto.imaging;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ImagingReportAttachmentRequestDTO extends ImagingReportAttachmentBaseDTO {
    // All fields inherited from base; no request-specific fields needed
}
