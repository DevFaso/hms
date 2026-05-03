package com.example.hms.payload.dto.superadmin;

import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Cross-tenant audit search page response (MVP-8). Mirrors Spring Data
 * pagination shape but stays a stable DTO so the frontend doesn't bind
 * directly to internal Spring types.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditSearchPageDTO {

    private List<AuditEventLogResponseDTO> content;
    private int pageNumber;
    private int pageSize;
    private long totalElements;
    private int totalPages;
}
