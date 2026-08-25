package com.example.hms.controller;

import com.example.hms.payload.dto.mortality.DeathRecordAmendmentDTO;
import com.example.hms.payload.dto.mortality.DeathRecordRequestDTO;
import com.example.hms.payload.dto.mortality.DeathRecordResponseDTO;
import com.example.hms.payload.dto.mortality.MortalityRegisterDTO;
import com.example.hms.payload.dto.mortality.RecordDeathResponseDTO;
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
 * every authenticated principal — and this endpoint set both writes an
 * irreversible state and reads cause-of-death detail about named people.
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
        "hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','SURGEON','MIDWIFE')";

    /** Who may read the register and individual records. */
    private static final String READER =
        "hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','DOCTOR','SURGEON','MIDWIFE',"
            + "'NURSE','QUALITY_MANAGER')";

    private final MortalityService mortalityService;

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
                   + "and stillbirth counts broken out — the numbers the facility is measured on. "
                   + "Late maternal deaths are counted separately because including them would "
                   + "overstate the maternal mortality ratio.")
    public ResponseEntity<MortalityRegisterDTO> getRegister(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(mortalityService.getRegister(from, to));
    }
}
