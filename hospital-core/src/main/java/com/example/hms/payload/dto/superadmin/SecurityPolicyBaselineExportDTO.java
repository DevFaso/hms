package com.example.hms.payload.dto.superadmin;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityPolicyBaselineExportDTO {

    private String baselineVersion;
    private String fileName;
    private String contentType;
    private String base64Content;
    private OffsetDateTime generatedAt;
}
