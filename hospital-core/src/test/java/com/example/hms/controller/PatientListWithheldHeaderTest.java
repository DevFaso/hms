package com.example.hms.controller;

import com.example.hms.controller.support.RecordsWithheldHeaders;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.ConsultationService;
import com.example.hms.service.ImagingOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D610 (D504 not fixed in part): under the patient's sharing opt-out,
 * DOCTOR_B's GET /imaging/orders/patient/{A3} and the consultations list
 * answered 200 [] with no X-Records-Withheld, so the empty list read as the
 * whole record. Each list now asks {@link RecordsWithheldHeaders} with the
 * acting hospital, as allergies, medications, vitals and lab results do.
 * (The referrals list is covered in {@code GeneralReferralControllerTest}.)
 */
class PatientListWithheldHeaderTest {

    private final UUID patientId = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();
    private final Authentication auth = mock(Authentication.class);
    private final RecordsWithheldHeaders headers = mock(RecordsWithheldHeaders.class);

    @BeforeEach
    void actAtHospitalB() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalB)
            .permittedHospitalIds(Set.of(hospitalB))
            .build());
        HttpHeaders withheld = new HttpHeaders();
        withheld.add(RecordsWithheldHeaders.HEADER, RecordsWithheldHeaders.OTHER_HOSPITALS);
        when(headers.forRead(any(), eq(patientId), eq(hospitalB))).thenReturn(withheld);
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("GET /imaging/orders/patient/{id} carries X-Records-Withheld for the acting hospital")
    void imagingListSaysWhenRowsAreWithheld() {
        ImagingOrderService service = mock(ImagingOrderService.class);
        when(service.getOrdersByPatient(patientId, null)).thenReturn(List.of());

        ResponseEntity<?> response = new ImagingOrderController(service, headers)
            .getOrdersByPatient(patientId, null, auth);

        assertThat(response.getHeaders().getFirst(RecordsWithheldHeaders.HEADER))
            .isEqualTo(RecordsWithheldHeaders.OTHER_HOSPITALS);
        verify(headers).forRead(auth, patientId, hospitalB);
    }

    @Test
    @DisplayName("GET /consultations/patient/{id} carries X-Records-Withheld for the acting hospital")
    void consultationListSaysWhenRowsAreWithheld() {
        ConsultationService service = mock(ConsultationService.class);
        when(service.getConsultationsForPatient(patientId)).thenReturn(List.of());

        ResponseEntity<?> response = new ConsultationController(service, headers)
            .getConsultationsForPatient(patientId, auth);

        assertThat(response.getHeaders().getFirst(RecordsWithheldHeaders.HEADER))
            .isEqualTo(RecordsWithheldHeaders.OTHER_HOSPITALS);
        verify(headers).forRead(auth, patientId, hospitalB);
    }
}
