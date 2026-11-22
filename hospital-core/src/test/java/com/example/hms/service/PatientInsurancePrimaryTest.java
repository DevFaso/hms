package com.example.hms.service;

import com.example.hms.enums.ActingMode;
import com.example.hms.mapper.PatientInsuranceMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientInsurance;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LinkPatientInsuranceRequestDTO;
import com.example.hms.payload.dto.PatientInsuranceResponseDTO;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.ActingContext;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D3250 (r70): exactly one primary coverage per patient and hospital. A patient's only policy, linked without
 * « Définir comme principale », stayed non-primary, so the desk said « Problème d'assurance » and the check-in tick
 * recorded nothing while the invoice billed that very policy.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatientInsurancePrimaryTest {

    @Mock private PatientInsuranceRepository patientInsuranceRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private PatientInsuranceMapper patientInsuranceMapper;
    @Mock private MessageSource messageSource;
    @Mock private RoleValidator roleValidator;
    @Mock private PatientChartAccess patientChartAccess;
    @Mock private PatientSubjectReadGuard subjectReadGuard;
    @Mock private com.example.hms.security.tenant.ActingScopeResolver actingScopeResolver;
    @Mock private com.example.hms.service.billing.PayerCatalogueService payerCatalogueService;

    @InjectMocks private PatientInsuranceServiceImpl service;

    private final UUID patientId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private Patient patient;
    private UserRoleHospitalAssignment assignment;
    private final List<PatientInsurance> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(userId);
        patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setUser(user);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        assignment = new UserRoleHospitalAssignment();
        assignment.setHospital(hospital);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(patientInsuranceRepository.save(any(PatientInsurance.class))).thenAnswer(inv -> {
            PatientInsurance row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
                row.setCreatedAt(LocalDateTime.now().minusMinutes(rows.size()));
                rows.add(row);
            }
            return row;
        });
        when(patientInsuranceRepository.findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalId))
            .thenReturn(rows);
        when(patientInsuranceRepository.findByIdAndAssignment_Hospital_Id(any(), eq(hospitalId))).thenAnswer(inv ->
            rows.stream().filter(row -> row.getId().equals(inv.getArgument(0))).findFirst());
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(any())).thenReturn(new PatientInsuranceResponseDTO());
    }

    private void link(String policy, Boolean primary) {
        when(patientInsuranceRepository
                .findFirstByPatient_IdAndAssignment_Hospital_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
                    patientId, hospitalId, "MUT-TEST-A", policy))
            .thenReturn(Optional.empty());
        when(patientInsuranceRepository
                .findFirstByPatient_IdAndAssignmentIsNullAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
                    patientId, "MUT-TEST-A", policy))
            .thenReturn(Optional.empty());
        service.upsertAndLinkByNaturalKey(LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId).payerCode("MUT-TEST-A").policyNumber(policy)
            .primary(primary).build(), new ActingContext(userId, hospitalId, ActingMode.STAFF, null), Locale.FRENCH);
    }

    @Test
    @DisplayName("QA D3250: the only policy, linked without « principale », becomes primary")
    void d3250_theOnlyPolicyBecomesPrimary() {
        link("MTA-T1-R70-0001", false);

        assertThat(rows).singleElement().satisfies(row -> assertThat(row.isPrimary()).isTrue());
    }

    @Test
    @DisplayName("QA D3250: a second policy linked without « principale » leaves the first one primary")
    void d3250_aSecondPolicyKeepsTheFirstPrimary() {
        link("MTA-T1-R70-0001", null);
        link("MTA-T1-R70-0002", false);

        assertThat(rows.get(0).isPrimary()).isTrue();
        assertThat(rows.get(1).isPrimary()).isFalse();
    }

    @Test
    @DisplayName("QA D3250: a policy ticked « principale » is the one primary; the others are unset")
    void d3250_aTickedPolicyIsTheOnlyPrimary() {
        link("MTA-T1-R70-0001", null);
        link("MTA-T1-R70-0002", true);

        PatientInsurance second = rows.get(1);
        assertThat(second.isPrimary()).isTrue();
        verify(patientInsuranceRepository, org.mockito.Mockito.atLeastOnce())
            .unsetOtherPrimariesForPatientInHospital(patientId, second.getId(),
            hospitalId);
        verify(patientInsuranceRepository, never()).unsetOtherPrimariesForPatient(any(), any());
    }
}
