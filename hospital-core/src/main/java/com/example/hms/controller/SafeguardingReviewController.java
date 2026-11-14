package com.example.hms.controller;

import com.example.hms.enums.SafeguardingReviewStatus;
import com.example.hms.payload.dto.safeguarding.SafeguardingReviewDTO;
import com.example.hms.payload.dto.safeguarding.SafeguardingReviewRecordRequestDTO;
import com.example.hms.service.safeguarding.SafeguardingReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * r53, provisional rule (user decision 2026-11-14, pending the obstetric lead's and the child-protection lead's
 * approval): the safeguarding reviews opened by the labour admission of a minor.
 *
 * <p>The ONLY way a safeguarding review leaves the server ({@code SafeguardingAccessGuardTest}). A new root, so no
 * {@code /patients/**} matcher (which admits reception, the patient and proxies) applies: the class-level
 * {@code @PreAuthorize} is load-bearing and names the treating clinical team and the hospital administrator only.
 * The service then requires the role AT the caller's acting hospital and answers another hospital's review as
 * unknown. Reception, billing, the patient, a proxy or family member, the native apps, exports and other hospitals
 * never read one.
 */
@RestController
@RequestMapping("/safeguarding")
@Validated
@RequiredArgsConstructor
@PreAuthorize(SafeguardingReviewController.READERS)
@Tag(name = "Safeguarding", description = "Child-protection vigilance reviews (provisional rule)")
public class SafeguardingReviewController {

    /** The treating clinical team and the hospital administrator. */
    public static final String READERS =
        "hasAnyRole('DOCTOR','PHYSICIAN','SURGEON','MIDWIFE','NURSE','HOSPITAL_ADMIN')";

    /** The responsible clinician records the review. */
    public static final String RECORDERS = "hasAnyRole('DOCTOR','PHYSICIAN','SURGEON')";

    private final SafeguardingReviewService service;

    @GetMapping("/reviews")
    @PreAuthorize(READERS)
    @Operation(summary = "Safeguarding reviews of the acting hospital (open by default)")
    public ResponseEntity<List<SafeguardingReviewDTO>> list(
        @RequestParam(required = false) SafeguardingReviewStatus status
    ) {
        return ResponseEntity.ok(service.listForHospital(status));
    }

    @GetMapping("/patients/{patientId}/reviews")
    @PreAuthorize(READERS)
    @Operation(summary = "One patient's safeguarding reviews at the acting hospital")
    public ResponseEntity<List<SafeguardingReviewDTO>> forPatient(@PathVariable UUID patientId) {
        return ResponseEntity.ok(service.listForPatient(patientId));
    }

    @PostMapping("/reviews/{reviewId}/review")
    @PreAuthorize(RECORDERS)
    @Operation(summary = "Record the documented safeguarding review",
               description = "Closes the review and clears its tasks. Never blocks or changes the patient's care.")
    public ResponseEntity<SafeguardingReviewDTO> record(
        @PathVariable UUID reviewId,
        @Valid @RequestBody SafeguardingReviewRecordRequestDTO request
    ) {
        return ResponseEntity.ok(service.recordReview(reviewId, request.getNote()));
    }
}
