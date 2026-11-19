package com.example.hms.controller;

import com.example.hms.payload.dto.imaging.ImagingReadingContextDTO;
import com.example.hms.service.imaging.ImagingReadingContextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * r63 (imaging completion): the order's reading context for the radiologist:
 * age, sex, pregnancy as recorded, the latest creatinine and the prior studies,
 * all read-only. Clinical readers only: an administrator reads released
 * reports, not the patient's labs (user decision 2026-11-14), and a patient
 * never reaches it.
 */
@RestController
@RequestMapping("/imaging")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('DOCTOR','NURSE','MIDWIFE','RADIOLOGIST')")
@Tag(name = "Imaging Orders", description = "Order intake and duplicate checking for radiology studies")
public class ImagingReadingContextController {

    private final ImagingReadingContextService readingContextService;

    @GetMapping("/orders/{orderId}/reading-context")
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','MIDWIFE','RADIOLOGIST')")
    @Operation(summary = "The order's reading context",
        description = "Age, recorded sex, the latest maternal history, the order's pregnancy-test flag, the latest "
            + "released creatinine and the patient's prior imaging studies, at the order's hospital. Nothing graded.")
    public ResponseEntity<ImagingReadingContextDTO> getReadingContext(@PathVariable UUID orderId) {
        return ResponseEntity.ok(readingContextService.contextOf(orderId));
    }
}
