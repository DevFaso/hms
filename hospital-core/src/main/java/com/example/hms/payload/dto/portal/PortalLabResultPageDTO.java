package com.example.hms.payload.dto.portal;

import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;

import java.util.List;

/**
 * QA D1213 (round 23): one page of the patient's own current lab results
 * ({@code GET /me/patient/lab-results/page?page=&size=}). Superseded and
 * corrected-away rows are removed BEFORE the page is cut, newest first with a
 * stable tie-break, so no released result falls between two pages.
 *
 * @param content the page's results
 * @param page    the zero-based page index served
 * @param size    the page size served (at most 100)
 * @param hasMore whether an older page exists
 */
public record PortalLabResultPageDTO(List<PatientLabResultResponseDTO> content, int page, int size, boolean hasMore) {
}
