package com.example.hms.controller;

import com.example.hms.enums.CashTill;
import com.example.hms.payload.dto.billing.CashSessionCloseRequestDTO;
import com.example.hms.payload.dto.billing.CashSessionDTO;
import com.example.hms.payload.dto.billing.CashSessionNoteRequestDTO;
import com.example.hms.service.billing.cash.CashSessionReports;
import com.example.hms.service.billing.cash.CashSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Billing-money plan PR 10 (QA r65 D2325, D2326, D2672, D2714): cash sessions. Plan section 3.8: every till holder
 * (hospital admin, accountant, billing specialist, receptionist, pharmacist) reads and closes their own session; a
 * hospital admin or an accountant sees every session of the hospital, reopens or reviews one (never their own), and
 * prints the day report; the platform super-admin reads. The service checks each role again at the acting hospital.
 */
@RestController
@RequestMapping("/cash-sessions")
@Tag(name = "Cash sessions", description = "Tills: counted close, lock, supervisor view and day report")
@RequiredArgsConstructor
public class CashSessionController {

    static final String TILL_HOLDERS = "hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT',"
        + "'ROLE_BILLING_SPECIALIST','ROLE_RECEPTIONIST','ROLE_PHARMACIST')";
    /** Own roles only (QA D2120): a super-admin's expanded authorities never close a till. */
    static final String CLOSERS = "T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, "
        + "'HOSPITAL_ADMIN','ACCOUNTANT','BILLING_SPECIALIST','RECEPTIONIST','PHARMACIST')";
    static final String SUPERVISOR_READERS = "hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT')";
    static final String SUPERVISORS = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_ACCOUNTANT')";

    private final CashSessionService sessions;
    private final CashSessionReports reports;

    @GetMapping("/mine")
    @PreAuthorize(TILL_HOLDERS)
    @Operation(summary = "The signed-in user's sessions of a day (« Ma caisse du jour »)")
    public ResponseEntity<List<CashSessionDTO>> mine(
        @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(sessions.mine(date));
    }

    @GetMapping
    @PreAuthorize(SUPERVISOR_READERS)
    @Operation(summary = "Every till of the hospital on a day (« Caisses du jour »)")
    public ResponseEntity<List<CashSessionDTO>> list(
        @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(name = "till", required = false) CashTill till) {
        return ResponseEntity.ok(sessions.list(date, till));
    }

    @GetMapping("/{id}")
    @PreAuthorize(TILL_HOLDERS)
    @Operation(summary = "One session with its movements")
    public ResponseEntity<CashSessionDTO> get(@PathVariable UUID id) {
        return ResponseEntity.ok(sessions.get(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(CLOSERS)
    @Operation(summary = "Count and close one's own session")
    public ResponseEntity<CashSessionDTO> close(@PathVariable UUID id,
                                                @Valid @RequestBody CashSessionCloseRequestDTO body) {
        return ResponseEntity.ok(sessions.close(id, body));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize(SUPERVISORS)
    @Operation(summary = "Reopen a closed session, with a reason")
    public ResponseEntity<CashSessionDTO> reopen(@PathVariable UUID id,
                                                 @Valid @RequestBody CashSessionNoteRequestDTO body) {
        return ResponseEntity.ok(sessions.reopen(id, body != null ? body.getNote() : null));
    }

    @PostMapping("/{id}/review")
    @PreAuthorize(SUPERVISORS)
    @Operation(summary = "Mark a closed session reviewed")
    public ResponseEntity<CashSessionDTO> review(@PathVariable UUID id,
                                                 @Valid @RequestBody(required = false) CashSessionNoteRequestDTO body) {
        return ResponseEntity.ok(sessions.review(id, body != null ? body.getNote() : null));
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize(TILL_HOLDERS)
    @Operation(summary = "One session's sheet (« Feuille de caisse », PDF)")
    public ResponseEntity<byte[]> sessionPdf(@PathVariable UUID id,
                                             @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return pdf(reports.sessionPdf(id, locale), "feuille-de-caisse-" + id);
    }

    @GetMapping("/day-report.pdf")
    @PreAuthorize(SUPERVISOR_READERS)
    @Operation(summary = "The hospital's day report, every till (PDF)")
    public ResponseEntity<byte[]> dayReportPdf(
        @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        CashSessionReports.Rendered rendered = reports.dayReportPdf(date, locale);
        return pdf(rendered.content(), rendered.fileName());
    }

    @GetMapping("/day-report.csv")
    @PreAuthorize(SUPERVISOR_READERS)
    @Operation(summary = "The day's journal of every money movement (CSV)")
    public ResponseEntity<byte[]> dayReportCsv(
        @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        CashSessionReports.Rendered rendered = reports.journalCsv(date);
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .cacheControl(CacheControl.noCache())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(rendered.fileName() + ".csv").build().toString())
            .contentLength(rendered.content().length)
            .body(rendered.content());
    }

    private static ResponseEntity<byte[]> pdf(byte[] content, String fileName) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .cacheControl(CacheControl.noCache())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(fileName + ".pdf").build().toString())
            .contentLength(content.length)
            .body(content);
    }
}
