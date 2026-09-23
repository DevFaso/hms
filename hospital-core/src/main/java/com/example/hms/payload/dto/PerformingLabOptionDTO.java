package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * A laboratory (hospital) a clinician can route a lab order to
 * ({@code GET /lab-orders/performing-labs}, audit gap B1). Deliberately
 * PHI-free and small: id, name and code are all the order form needs.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PerformingLabOptionDTO {
    private UUID id;
    private String name;
    private String code;
}
