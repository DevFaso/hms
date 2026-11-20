package com.example.hms.controller.pharmacy;

import com.example.hms.controller.HospitalBillingPolicyController;
import com.example.hms.controller.RefillApprovalController;
import com.example.hms.enums.DispenseBillingDeferralStatus;
import com.example.hms.payload.dto.billing.HospitalBillingPolicyDTO;
import com.example.hms.payload.dto.billing.HospitalBillingPolicyUpdateDTO;
import com.example.hms.payload.dto.pharmacy.DispenseBillingCounterDTO;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralDTO;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralDecisionDTO;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralReasonDTO;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralRequestDTO;
import com.example.hms.payload.dto.portal.MedicationRefillResponseDTO;
import com.example.hms.payload.dto.portal.RefillDecisionRequestDTO;
import com.example.hms.service.RefillApprovalService;
import com.example.hms.service.billing.HospitalBillingPolicyService;
import com.example.hms.service.pharmacy.DispenseBillingDeferralService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Policy rules 1 and 6 (V345): the new endpoints hand the service's answer back as it is. */
@DisplayName("Policy rule controllers: deferred billing, billing policy, pharmacy steps on a renewal")
class PolicyRuleControllersTest {

    private final UUID id = UUID.randomUUID();

    @Test
    @DisplayName("deferrals: list, counter, request (201), approve, reject, cancel, review delegate to the service")
    void deferrals() {
        DispenseBillingDeferralService service = mock(DispenseBillingDeferralService.class);
        DispenseBillingDeferralController controller = new DispenseBillingDeferralController(service);
        DispenseBillingDeferralDTO dto = DispenseBillingDeferralDTO.builder().id(id).status("REQUESTED").build();
        DispenseBillingCounterDTO counter = DispenseBillingCounterDTO.builder().onOpenStay(true).open(List.of()).build();
        DispenseBillingDeferralRequestDTO request = new DispenseBillingDeferralRequestDTO();
        DispenseBillingDeferralDecisionDTO decision = new DispenseBillingDeferralDecisionDTO();
        DispenseBillingDeferralReasonDTO reason = new DispenseBillingDeferralReasonDTO("motif");
        List<DispenseBillingDeferralStatus> statuses = List.of(DispenseBillingDeferralStatus.REQUESTED);
        when(service.list(statuses, id)).thenReturn(List.of(dto));
        when(service.counter(id)).thenReturn(counter);
        when(service.request(request)).thenReturn(dto);
        when(service.approve(id, decision)).thenReturn(dto);
        when(service.reject(id, reason)).thenReturn(dto);
        when(service.cancel(id, reason)).thenReturn(dto);
        when(service.review(id, reason)).thenReturn(dto);

        assertThat(controller.list(statuses, id).getBody().getData()).containsExactly(dto);
        assertThat(controller.counter(id).getBody().getData()).isSameAs(counter);
        ResponseEntity<?> created = controller.request(request);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.approve(id, decision).getBody().getData()).isSameAs(dto);
        assertThat(controller.reject(id, reason).getBody().getData()).isSameAs(dto);
        assertThat(controller.cancel(id, reason).getBody().getData()).isSameAs(dto);
        assertThat(controller.review(id, reason).getBody().getData()).isSameAs(dto);
    }

    @Test
    @DisplayName("billing policy: read and update delegate to the service")
    void billingPolicy() {
        HospitalBillingPolicyService service = mock(HospitalBillingPolicyService.class);
        HospitalBillingPolicyController controller = new HospitalBillingPolicyController(service);
        HospitalBillingPolicyDTO dto = HospitalBillingPolicyDTO.builder().autoConsultationOnPlannedAdmission(false)
                .pharmacistExceptionalRenewal(false).canEdit(true).build();
        HospitalBillingPolicyUpdateDTO update = new HospitalBillingPolicyUpdateDTO(true, null);
        when(service.current()).thenReturn(dto);
        when(service.update(update)).thenReturn(dto);

        assertThat(controller.current().getBody().getData()).isSameAs(dto);
        assertThat(controller.update(update).getBody().getData()).isSameAs(dto);
    }

    @Test
    @DisplayName("renewals: the pharmacy's three steps delegate to the service")
    void pharmacyStepsOnARenewal() {
        RefillApprovalService service = mock(RefillApprovalService.class);
        RefillApprovalController controller = new RefillApprovalController(service);
        Authentication auth = mock(Authentication.class);
        RefillDecisionRequestDTO note = RefillDecisionRequestDTO.builder().pharmacistNote("Demandé trop tôt").build();
        MedicationRefillResponseDTO dto = MedicationRefillResponseDTO.builder().id(id).status("REQUESTED").build();
        when(service.pharmacistReview(auth, id, note)).thenReturn(dto);
        when(service.pharmacistRefuse(auth, id, note)).thenReturn(dto);
        when(service.releaseAsPrescribed(auth, id)).thenReturn(dto);

        assertThat(controller.pharmacistReview(auth, id, note).getBody().getData()).isSameAs(dto);
        assertThat(controller.pharmacistRefuse(auth, id, note).getBody().getData()).isSameAs(dto);
        assertThat(controller.releaseAsPrescribed(auth, id).getBody().getData()).isSameAs(dto);
    }
}
