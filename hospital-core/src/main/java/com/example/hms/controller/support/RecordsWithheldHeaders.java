package com.example.hms.controller.support;

import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * QA D504: a patient clinical list narrowed by the patient's sharing opt-out
 * carries {@value #HEADER}, so it never reads as the whole record. The header
 * names the fact, never the hidden rows, and says nothing about whether any
 * exist.
 */
@Component
@RequiredArgsConstructor
public class RecordsWithheldHeaders {

    public static final String HEADER = "X-Records-Withheld";
    public static final String OTHER_HOSPITALS = "OTHER_HOSPITALS";

    private final RecordAccessPolicy recordAccessPolicy;
    private final ControllerAuthUtils authUtils;

    /** Empty headers unless the opt-out holds back other hospitals' rows from this read. */
    public HttpHeaders forRead(Authentication auth, UUID patientId, UUID actingHospitalId) {
        HttpHeaders headers = new HttpHeaders();
        if (patientId == null || actingHospitalId == null) {
            return headers;
        }
        UUID actor = authUtils.resolveUserId(auth).orElse(null);
        if (recordAccessPolicy.foreignRowsWithheld(actor, patientId, actingHospitalId)) {
            headers.add(HEADER, OTHER_HOSPITALS);
        }
        return headers;
    }
}
