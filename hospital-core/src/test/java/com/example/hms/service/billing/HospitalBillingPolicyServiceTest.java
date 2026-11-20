package com.example.hms.service.billing;

import com.example.hms.enums.AdmissionType;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.HospitalBillingPolicy;
import com.example.hms.payload.dto.billing.HospitalBillingPolicyDTO;
import com.example.hms.payload.dto.billing.HospitalBillingPolicyUpdateDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.EncounterTreatmentRepository;
import com.example.hms.repository.HospitalBillingPolicyRepository;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Policy rules 1 and 6 (provisional, V345): a hospital's own billing policy. Its defaults are the report's rules; a
 * hospital admin of the hospital changes the consultation default; the pharmacist-renewal flag stays off.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HospitalBillingPolicyServiceTest {

    @Mock private HospitalBillingPolicyRepository policyRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private EncounterTreatmentRepository encounterTreatmentRepository;
    @Mock private AdmissionRepository admissionRepository;

    private HospitalBillingPolicyService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        service = new HospitalBillingPolicyService(policyRepository, roleValidator);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(admin);
        when(roleValidator.canSetHospitalBillingPolicy(admin, hospitalId)).thenReturn(true);
        when(policyRepository.save(any(HospitalBillingPolicy.class))).thenAnswer(saved -> saved.getArgument(0));
    }

    @Test
    @DisplayName("a hospital that never chose uses the report's rules: no automatic consultation, no pharmacist renewal")
    void theDefaultsAreTheReportsRules() {
        when(policyRepository.findByHospitalId(hospitalId)).thenReturn(Optional.empty());

        HospitalBillingPolicyDTO policy = service.current();

        assertThat(policy.getAutoConsultationOnPlannedAdmission()).isFalse();
        assertThat(policy.getPharmacistExceptionalRenewal()).isFalse();
        assertThat(policy.isCanEdit()).isTrue();
        assertThat(service.autoConsultationOnPlannedAdmission(hospitalId)).isFalse();
        assertThat(service.autoConsultationOnPlannedAdmission(null)).isFalse();
    }

    @Test
    @DisplayName("a hospital admin changes the consultation default: the row is written for that hospital and read back")
    void anAdminChangesTheConsultationDefault() {
        when(policyRepository.findByHospitalId(hospitalId)).thenReturn(Optional.empty());

        HospitalBillingPolicyDTO updated = service.update(new HospitalBillingPolicyUpdateDTO(true, null));

        ArgumentCaptor<HospitalBillingPolicy> row = ArgumentCaptor.forClass(HospitalBillingPolicy.class);
        verify(policyRepository).save(row.capture());
        assertThat(row.getValue().getHospitalId()).isEqualTo(hospitalId);
        assertThat(row.getValue().isAutoConsultationOnPlannedAdmission()).isTrue();
        assertThat(row.getValue().getUpdatedBy()).isEqualTo(admin);
        assertThat(updated.getAutoConsultationOnPlannedAdmission()).isTrue();

        when(policyRepository.findByHospitalId(hospitalId)).thenReturn(Optional.of(row.getValue()));
        assertThat(service.current().getAutoConsultationOnPlannedAdmission()).isTrue();
        assertThat(service.autoConsultationOnPlannedAdmission(hospitalId)).isTrue();
    }

    @Test
    @DisplayName("the pharmacist-renewal flag stays off: turning it on is refused and nothing is written")
    void thePharmacistRenewalFlagStaysOff() {
        assertThatThrownBy(() -> service.update(new HospitalBillingPolicyUpdateDTO(null, true)))
                .isInstanceOf(FieldBusinessException.class)
                .extracting("field", "messageKey")
                .containsExactly("pharmacistExceptionalRenewal", "billing.policy.pharmacistRenewalUnavailable");
        verify(policyRepository, never()).save(any());
    }

    @Test
    @DisplayName("only a hospital admin of the hospital changes it; a field left out keeps its value")
    void onlyAnAdminAndOnlyWhatIsSent() {
        HospitalBillingPolicy stored = HospitalBillingPolicy.builder().hospitalId(hospitalId)
                .autoConsultationOnPlannedAdmission(true).build();
        stored.setUpdatedAt(LocalDateTime.now());
        when(policyRepository.findByHospitalId(hospitalId)).thenReturn(Optional.of(stored));

        service.update(new HospitalBillingPolicyUpdateDTO(null, false));
        assertThat(stored.isAutoConsultationOnPlannedAdmission()).as("left out: kept").isTrue();

        when(roleValidator.canSetHospitalBillingPolicy(admin, hospitalId)).thenReturn(false);
        assertThatThrownBy(() -> service.update(new HospitalBillingPolicyUpdateDTO(false, null)))
                .isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThat(stored.isAutoConsultationOnPlannedAdmission()).isTrue();
        assertThat(service.current().isCanEdit()).isFalse();
    }

    // ── Rule 6: the consultation line reads the hospital's choice ────────────────────────────

    private List<VisitChargeSource.VisitCharge> consultationLines(AdmissionType type) {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Admission stay = new Admission();
        stay.setId(UUID.randomUUID());
        stay.setAdmissionType(type);
        Encounter visit = new Encounter();
        visit.setId(UUID.randomUUID());
        visit.setHospital(hospital);
        visit.setAdmissionId(stay.getId());
        when(admissionRepository.findById(stay.getId())).thenReturn(Optional.of(stay));
        when(encounterTreatmentRepository.findByEncounter_Id(visit.getId())).thenReturn(List.of());
        ConsultationChargeSource source = new ConsultationChargeSource(encounterTreatmentRepository,
                admissionRepository);
        source.setBillingPolicy(service);
        return source.charges(new VisitChargeSource.Visit(visit, LocalDateTime.now(),
                new VisitPriceList(List.of()), null));
    }

    @ParameterizedTest
    @EnumSource(value = AdmissionType.class, names = {"ELECTIVE", "DAY_CASE", "NEWBORN", "TRANSFER", "LABOR_DELIVERY"})
    @DisplayName("by default a planned, day-case, newborn, labour or transfer admission gets no consultation line")
    void noAutomaticConsultationByDefault(AdmissionType type) {
        when(policyRepository.findByHospitalId(hospitalId)).thenReturn(Optional.empty());

        assertThat(consultationLines(type)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = AdmissionType.class, names = {"ELECTIVE", "DAY_CASE", "NEWBORN", "TRANSFER", "LABOR_DELIVERY"})
    @DisplayName("a hospital that chose the default consultation gets exactly one line on such an admission")
    void aHospitalMayChooseTheDefaultConsultation(AdmissionType type) {
        when(policyRepository.findByHospitalId(hospitalId)).thenReturn(Optional.of(
                HospitalBillingPolicy.builder().hospitalId(hospitalId).autoConsultationOnPlannedAdmission(true).build()));

        assertThat(consultationLines(type)).hasSize(1);
    }

    @Test
    @DisplayName("an emergency admission keeps its one consultation line whatever the hospital chose")
    void anEmergencyAdmissionKeepsItsLine() {
        when(policyRepository.findByHospitalId(hospitalId)).thenReturn(Optional.empty());

        assertThat(consultationLines(AdmissionType.EMERGENCY)).hasSize(1);
    }
}
