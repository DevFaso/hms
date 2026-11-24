package com.example.hms.controller;

import com.example.hms.service.PatientRecordPdfService;
import com.example.hms.service.roi.RoiRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Reuses the chart export's clinical permissions and redaction; never widens them to the worklist's readers. */
@RestController @RequiredArgsConstructor
public class RoiRecordExportController {
    private final RoiRequestService requests;
    private final PatientRecordPdfService pdf;
    @GetMapping(value = "/roi-requests/{requestId}/record.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(PatientRecordExportController.EXPORT_ROLES)
    public ResponseEntity<byte[]> download(@PathVariable UUID requestId) {
        byte[] bytes = pdf.renderForRoi(requests.copyForExport(requestId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"record-copy-" + requestId + ".pdf\"")
            .header("X-Content-Type-Options", "nosniff").contentType(MediaType.APPLICATION_PDF).body(bytes);
    }
}
