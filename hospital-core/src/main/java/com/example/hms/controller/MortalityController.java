package com.example.hms.controller;

import com.example.hms.payload.dto.mortality.DeathDisputeOutcomeDTO;
import com.example.hms.payload.dto.mortality.DeathDisputeRequestDTO;
import com.example.hms.payload.dto.mortality.DeathDisputeResponseDTO;
import com.example.hms.payload.dto.mortality.DeathRecordAmendmentDTO;
import com.example.hms.payload.dto.mortality.DeathRecordEnteredInErrorDTO;
import com.example.hms.payload.dto.mortality.DeathRecordRequestDTO;
import com.example.hms.payload.dto.mortality.DeathRecordResponseDTO;
import com.example.hms.payload.dto.mortality.MortalityRegisterDTO;
import com.example.hms.payload.dto.mortality.RecordDeathResponseDTO;
import com.example.hms.service.DeathDisputeService;
import com.example.hms.service.MortalityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Death and mortality (Tier 2 item 29).
 *
 * <p>A new root for the same reason the transfusion module took one: both
 * {@code /patients/**} and {@code /encounters/**} carry SecurityConfig matchers
 * whose first-match behaviour has already produced live 403 defects, and a
 * fresh root rides {@code anyRequest().authenticated()} with nothing to fight.
 *
 * <p>Which makes the <b>class-level {@code @PreAuthorize} load-bearing</b>: with
 * no matcher, a method whose annotation was forgotten would be reachable by
 * every authenticated principal: this endpoint set both writes a patient's
 * death and reads cause-of-death detail about named people.
 *
 * <p>Recording a death is deliberately narrow: a clinician or a records officer,
 * not the whole desk. Reading the register is wider, because the people who
 * report the facility's mortality numbers are not the people who certify.
 */
@RestController
@RequestMapping("/mortality")
@Validated
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','DOCTOR','SURGEON','MIDWIFE',"
    + "'NURSE','QUALITY_MANAGER')")
@Tag(name = "Mortality", description = "Death records, the mortality register, and maternal/perinatal reporting")
public class MortalityController {

    /** Who may certify or record a death. */
    private static final String RECORDER =
        "hasAnyRole('SUPER_ADMIN','DOCTOR','SURGEON','MIDWIFE')";

    /** QA D1488: who may mark a death record entered in error; the service asks for the role at the record's hospital. */
    private static final String CORRECTOR = "hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN')";

    /** Who may read the register and individual records. */
    private static final String READER =
        "hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','DOCTOR','SURGEON','MIDWIFE',"
            + "'NURSE','QUALITY_MANAGER')";

    private final MortalityService mortalityService;
    private final DeathDisputeService disputeService;

    @PostMapping("/deaths")
    @PreAuthorize(RECORDER)
    @Operation(summary = "Record a death",
               description = "Writes the certificate, marks the patient deceased, and closes what is "
                   + "still open — admissions, encounters, future appointments and pending recalls. "
                   + "The response reports exactly what was closed. Refused if a death is already "
                   + "on record for this patient.")
    public ResponseEntity<RecordDeathResponseDTO> recordDeath(
        @Valid @RequestBody DeathRecordRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mortalityService.recordDeath(request));
    }

    @PostMapping("/deaths/{recordId}/amend")
    @PreAuthorize(RECORDER)
    @Operation(summary = "Amend a cause of death",
               description = "What an autopsy or coroner routinely changes weeks later. A reason is "
                   + "required. Cannot alter the time of death or the patient — the account is "
                   + "amendable, the fact is not.")
    public ResponseEntity<DeathRecordResponseDTO> amendDeathRecord(
        @PathVariable UUID recordId,
        @Valid @RequestBody DeathRecordAmendmentDTO request
    ) {
        return ResponseEntity.ok(mortalityService.amendDeathRecord(recordId, request));
    }

    @PostMapping("/deaths/{recordId}/entered-in-error")
    @PreAuthorize(CORRECTOR)
    @Operation(summary = "Mark a death record entered in error",
               description = "For a death recorded on the wrong patient. The row is kept with who, when and "
                   + "why, it leaves the register and the indicators, the patient is no longer deceased and "
                   + "care reopens. Stays, visits and appointments the death closed are not reopened.")
    public ResponseEntity<DeathRecordResponseDTO> markEnteredInError(
        @PathVariable UUID recordId,
        @Valid @RequestBody DeathRecordEnteredInErrorDTO request
    ) {
        return ResponseEntity.ok(mortalityService.markEnteredInError(recordId, request));
    }

    @PostMapping("/patients/{patientId}/disputes")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Dispute a recorded death, urgently",
               description = "A doctor with an active assignment at this hospital, who has assessed the patient as "
                   + "alive and verified who they are. Lifts the death's block on care here, at once, pending review. "
                   + "The death record is kept as it is. A repeat answers with the open dispute.")
    public ResponseEntity<DeathDisputeResponseDTO> disputeDeath(
        @PathVariable UUID patientId,
        @Valid @RequestBody DeathDisputeRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(disputeService.dispute(patientId, request));
    }

    @GetMapping("/disputes")
    @PreAuthorize(CORRECTOR)
    @Operation(summary = "The open disputes this hospital has to review")
    public ResponseEntity<List<DeathDisputeResponseDTO>> openDisputes() {
        return ResponseEntity.ok(disputeService.openForReview());
    }

    @PostMapping("/disputes/{disputeId}/uphold")
    @PreAuthorize(CORRECTOR)
    @Operation(summary = "Death upheld: the block on care is back at the disputing hospital",
               description = "The other outcome is marking the death record entered in error. A reason is required.")
    public ResponseEntity<DeathDisputeResponseDTO> upholdDeath(
        @PathVariable UUID disputeId,
        @Valid @RequestBody DeathDisputeOutcomeDTO outcome
    ) {
        return ResponseEntity.ok(disputeService.uphold(disputeId, outcome));
    }

    @GetMapping("/deaths/patient/{patientId}")
    @PreAuthorize(READER)
    @Operation(summary = "The death record for one patient")
    public ResponseEntity<DeathRecordResponseDTO> getForPatient(@PathVariable UUID patientId) {
        return ResponseEntity.ok(mortalityService.getForPatient(patientId));
    }

    @GetMapping("/register")
    @PreAuthorize(READER)
    @Operation(summary = "The mortality register for a period",
               description = "Total deaths with maternal (WHO definition), late maternal, perinatal "
                   + "(WHO definition: stillbirths and days 0-6, from 22 weeks of gestation), stillbirth, "
                   + "early neonatal and late neonatal counts broken out — the numbers the facility is "
                   + "measured on. Late neonatal deaths are outside the perinatal count. "
                   + "Late maternal deaths are counted separately because including them would "
                   + "overstate the maternal mortality ratio.")
    public ResponseEntity<MortalityRegisterDTO> getRegister(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(mortalityService.getRegister(from, to));
    }
}
