package com.example.hms.service;

import com.example.hms.enums.ReferralSpecialty;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralType;
import com.example.hms.enums.ReferralUrgency;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.GeneralReferral;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.GeneralReferralRequestDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.GeneralReferralRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.impl.GeneralReferralServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneralReferralServiceImplTest {

    @Mock private GeneralReferralRepository referralRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private DepartmentRepository departmentRepository;

    @InjectMocks
    private GeneralReferralServiceImpl service;

    private final UUID patientId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID referringProviderId = UUID.randomUUID();
    private final UUID receivingProviderId = UUID.randomUUID();
    private final UUID departmentId = UUID.randomUUID();
    private final UUID referralId = UUID.randomUUID();

    private Patient patient;
    private Hospital hospital;
    private Staff referringProvider;
    private Staff receivingProvider;
    private Department department;

    @BeforeEach
    void setUp() {
        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Jane");
        patient.setLastName("Smith");

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("City Hospital");

        referringProvider = new Staff();
        referringProvider.setId(referringProviderId);

        receivingProvider = new Staff();
        receivingProvider.setId(receivingProviderId);

        department = new Department();
        department.setId(departmentId);
        department.setName("Cardiology");
    }

    private GeneralReferralRequestDTO buildRequest() {
        GeneralReferralRequestDTO dto = new GeneralReferralRequestDTO();
        dto.setPatientId(patientId);
        dto.setHospitalId(hospitalId);
        dto.setReferringProviderId(referringProviderId);
        dto.setTargetSpecialty(ReferralSpecialty.CARDIOLOGY);
        dto.setReferralType(ReferralType.CONSULTATION);
        dto.setUrgency(ReferralUrgency.ROUTINE);
        dto.setReferralReason("Evaluation needed");
        return dto;
    }

    private GeneralReferral buildReferral() {
        GeneralReferral r = new GeneralReferral();
        r.setId(referralId);
        r.setPatient(patient);
        r.setHospital(hospital);
        r.setReferringProvider(referringProvider);
        r.setStatus(ReferralStatus.DRAFT);
        r.setTargetSpecialty(ReferralSpecialty.CARDIOLOGY);
        r.setReferralType(ReferralType.CONSULTATION);
        r.setUrgency(ReferralUrgency.ROUTINE);
        r.setReferralReason("Evaluation needed");
        return r;
    }

    // ── createReferral ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("createReferral")
    class CreateReferral {

        @Test
        @DisplayName("creates referral without optional fields")
        void createMinimal() {
            GeneralReferralRequestDTO request = buildRequest();

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(staffRepository.findById(referringProviderId)).thenReturn(Optional.of(referringProvider));
            when(referralRepository.save(any(GeneralReferral.class))).thenAnswer(inv -> {
                GeneralReferral r = inv.getArgument(0);
                r.setId(referralId);
                return r;
            });

            GeneralReferralResponseDTO result = service.createReferral(request);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(ReferralStatus.DRAFT);
            assertThat(result.getPriorityScore()).isEqualTo(25);
        }

        @Test
        @DisplayName("creates referral with receiving provider and department")
        void createWithOptionalFields() {
            GeneralReferralRequestDTO request = buildRequest();
            request.setReceivingProviderId(receivingProviderId);
            request.setTargetDepartmentId(departmentId);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(staffRepository.findById(referringProviderId)).thenReturn(Optional.of(referringProvider));
            when(staffRepository.findById(receivingProviderId)).thenReturn(Optional.of(receivingProvider));
            when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
            when(referralRepository.save(any(GeneralReferral.class))).thenAnswer(inv -> {
                GeneralReferral r = inv.getArgument(0);
                r.setId(referralId);
                return r;
            });

            GeneralReferralResponseDTO result = service.createReferral(request);

            assertThat(result.getReceivingProviderId()).isEqualTo(receivingProviderId);
            assertThat(result.getTargetDepartmentId()).isEqualTo(departmentId);
        }

        @Test
        @DisplayName("priority score for EMERGENCY urgency")
        void priorityScoreEmergency() {
            GeneralReferralRequestDTO request = buildRequest();
            request.setUrgency(ReferralUrgency.EMERGENCY);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(staffRepository.findById(referringProviderId)).thenReturn(Optional.of(referringProvider));
            when(referralRepository.save(any(GeneralReferral.class))).thenAnswer(inv -> {
                GeneralReferral r = inv.getArgument(0);
                r.setId(referralId);
                return r;
            });

            GeneralReferralResponseDTO result = service.createReferral(request);

            assertThat(result.getPriorityScore()).isEqualTo(100);
        }

        @Test
        @DisplayName("priority score for URGENT urgency")
        void priorityScoreUrgent() {
            GeneralReferralRequestDTO request = buildRequest();
            request.setUrgency(ReferralUrgency.URGENT);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(staffRepository.findById(referringProviderId)).thenReturn(Optional.of(referringProvider));
            when(referralRepository.save(any(GeneralReferral.class))).thenAnswer(inv -> {
                GeneralReferral r = inv.getArgument(0);
                r.setId(referralId);
                return r;
            });

            GeneralReferralResponseDTO result = service.createReferral(request);

            assertThat(result.getPriorityScore()).isEqualTo(75);
        }

        @Test
        @DisplayName("priority score for PRIORITY urgency")
        void priorityScorePriority() {
            GeneralReferralRequestDTO request = buildRequest();
            request.setUrgency(ReferralUrgency.PRIORITY);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(staffRepository.findById(referringProviderId)).thenReturn(Optional.of(referringProvider));
            when(referralRepository.save(any(GeneralReferral.class))).thenAnswer(inv -> {
                GeneralReferral r = inv.getArgument(0);
                r.setId(referralId);
                return r;
            });

            GeneralReferralResponseDTO result = service.createReferral(request);

            assertThat(result.getPriorityScore()).isEqualTo(50);
        }

        @Test
        @DisplayName("throws when patient not found")
        void throwsWhenPatientNotFound() {
            GeneralReferralRequestDTO request = buildRequest();
            when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createReferral(request))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("throws when receiving provider not found")
        void throwsWhenReceivingProviderNotFound() {
            GeneralReferralRequestDTO request = buildRequest();
            request.setReceivingProviderId(receivingProviderId);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(staffRepository.findById(referringProviderId)).thenReturn(Optional.of(referringProvider));
            when(staffRepository.findById(receivingProviderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createReferral(request))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("handles null medications list via copyList")
        void handlesNullMedications() {
            GeneralReferralRequestDTO request = buildRequest();
            request.setCurrentMedications(null);
            request.setDiagnoses(null);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(staffRepository.findById(referringProviderId)).thenReturn(Optional.of(referringProvider));
            when(referralRepository.save(any(GeneralReferral.class))).thenAnswer(inv -> {
                GeneralReferral r = inv.getArgument(0);
                r.setId(referralId);
                return r;
            });

            GeneralReferralResponseDTO result = service.createReferral(request);

            assertThat(result).isNotNull();
        }
    }

    // ── getReferral ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getReferral returns DTO")
    void getReferral() {
        GeneralReferral referral = buildReferral();
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));

        GeneralReferralResponseDTO result = service.getReferral(referralId);

        assertThat(result.getId()).isEqualTo(referralId);
    }

    @Test
    @DisplayName("getReferral throws when not found")
    void getReferralThrows() {
        when(referralRepository.findById(referralId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getReferral(referralId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── submitReferral ──────────────────────────────────────────────────────

    @Test
    @DisplayName("submitReferral transitions draft to submitted")
    void submitReferral() {
        GeneralReferral referral = buildReferral();
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(any(GeneralReferral.class))).thenAnswer(inv -> inv.getArgument(0));

        GeneralReferralResponseDTO result = service.submitReferral(referralId);

        assertThat(result.getStatus()).isEqualTo(ReferralStatus.SUBMITTED);
    }

    // ── acknowledgeReferral ─────────────────────────────────────────────────

    @Test
    @DisplayName("acknowledgeReferral sets receiving provider and notes")
    void acknowledgeReferral() {
        GeneralReferral referral = buildReferral();
        referral.setStatus(ReferralStatus.SUBMITTED);
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(staffRepository.findById(receivingProviderId)).thenReturn(Optional.of(receivingProvider));
        when(referralRepository.save(any(GeneralReferral.class))).thenAnswer(inv -> inv.getArgument(0));

        GeneralReferralResponseDTO result = service.acknowledgeReferral(referralId, "Acknowledged", receivingProviderId);

        assertThat(result.getStatus()).isEqualTo(ReferralStatus.ACKNOWLEDGED);
    }

    // ── completeReferral ────────────────────────────────────────────────────

    @Test
    @DisplayName("completeReferral sets summary and follow-up")
    void completeReferral() {
        GeneralReferral referral = buildReferral();
        referral.setStatus(ReferralStatus.ACKNOWLEDGED);
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(any(GeneralReferral.class))).thenAnswer(inv -> inv.getArgument(0));

        GeneralReferralResponseDTO result = service.completeReferral(referralId, "Summary text", "Follow-up plan");

        assertThat(result.getStatus()).isEqualTo(ReferralStatus.COMPLETED);
    }

    // ── cancelReferral ──────────────────────────────────────────────────────

    @Test
    @DisplayName("cancelReferral sets cancellation reason")
    void cancelReferral() {
        GeneralReferral referral = buildReferral();
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(any(GeneralReferral.class))).thenAnswer(inv -> inv.getArgument(0));

        service.cancelReferral(referralId, "Patient declined");

        verify(referralRepository).save(any(GeneralReferral.class));
    }

    // ── list methods ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getReferralsByPatient delegates to repository")
    void getReferralsByPatient() {
        when(referralRepository.findByPatientIdOrderByCreatedAtDesc(patientId)).thenReturn(List.of());

        List<GeneralReferralResponseDTO> result = service.getReferralsByPatient(patientId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getReferralsByReferringProvider delegates to repository")
    void getReferralsByReferringProvider() {
        when(referralRepository.findByReferringProviderIdOrderByCreatedAtDesc(referringProviderId))
                .thenReturn(List.of());

        List<GeneralReferralResponseDTO> result = service.getReferralsByReferringProvider(referringProviderId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getReferralsByReceivingProvider delegates to repository")
    void getReferralsByReceivingProvider() {
        when(referralRepository.findByReceivingProviderIdOrderByCreatedAtDesc(receivingProviderId))
                .thenReturn(List.of());

        List<GeneralReferralResponseDTO> result = service.getReferralsByReceivingProvider(receivingProviderId);

        assertThat(result).isEmpty();
    }

    // ── getReferralsByHospital ───────────────────────────────────────────────

    @Nested
    @DisplayName("getReferralsByHospital")
    class GetReferralsByHospital {

        @Test
        @DisplayName("filters by status when provided")
        void filtersByStatus() {
            when(referralRepository.findByHospitalIdAndStatusOrderByCreatedAtDesc(hospitalId, ReferralStatus.SUBMITTED))
                    .thenReturn(List.of());

            List<GeneralReferralResponseDTO> result = service.getReferralsByHospital(hospitalId, "SUBMITTED");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns all when status is null")
        void returnsAllWhenNull() {
            when(referralRepository.findByHospitalIdOrderByCreatedAtDesc(hospitalId)).thenReturn(List.of());

            List<GeneralReferralResponseDTO> result = service.getReferralsByHospital(hospitalId, null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns all when status is blank")
        void returnsAllWhenBlank() {
            when(referralRepository.findByHospitalIdOrderByCreatedAtDesc(hospitalId)).thenReturn(List.of());

            List<GeneralReferralResponseDTO> result = service.getReferralsByHospital(hospitalId, "  ");

            assertThat(result).isEmpty();
        }
    }

    // ── getAllReferrals ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAllReferrals")
    class GetAllReferrals {

        @Test
        @DisplayName("filters by status when provided")
        void filtersByStatus() {
            when(referralRepository.findByStatusOrderByCreatedAtDesc(ReferralStatus.COMPLETED))
                    .thenReturn(List.of());

            List<GeneralReferralResponseDTO> result = service.getAllReferrals("COMPLETED");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns all when status is null")
        void returnsAllWhenNull() {
            when(referralRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

            List<GeneralReferralResponseDTO> result = service.getAllReferrals(null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns all when status is blank")
        void returnsAllWhenBlank() {
            when(referralRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

            List<GeneralReferralResponseDTO> result = service.getAllReferrals("  ");

            assertThat(result).isEmpty();
        }
    }

    // ── getOverdueReferrals ─────────────────────────────────────────────────

    @Test
    @DisplayName("getOverdueReferrals delegates to repository")
    void getOverdueReferrals() {
        when(referralRepository.findOverdueReferrals(any())).thenReturn(List.of());

        List<GeneralReferralResponseDTO> result = service.getOverdueReferrals();

        assertThat(result).isEmpty();
    }
}
