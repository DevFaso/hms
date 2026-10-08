package com.example.hms.service;

import com.example.hms.controller.UserController;
import com.example.hms.enums.FacilityType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.payload.dto.BillingInvoiceRequestDTO;
import com.example.hms.payload.dto.EncounterRequestDTO;
import com.example.hms.payload.dto.GeneralReferralRequestDTO;
import com.example.hms.payload.dto.PatientHospitalRegistrationRequestDTO;
import com.example.hms.payload.dto.consultation.ConsultationRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralCreateRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.impl.ConsultationServiceImpl;
import com.example.hms.service.impl.GeneralReferralServiceImpl;
import com.example.hms.service.impl.ObgynReferralServiceImpl;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Provider plan AC-11, item 4: every user-supplied clinical destination named
 * with a provider facility (a pharmacy here) gets exactly the not-found answer
 * an unknown hospital gets at that site. Each nested class holds one site;
 * each fails when exactly its {@code ClinicalHospitals} filter is reverted.
 */
@DisplayName("requireClinicalHospital at every user-supplied destination")
class RequireClinicalHospitalTest {

    static final UUID PROVIDER_ID = UUID.randomUUID();
    static final UUID HOSPITAL_ID = UUID.randomUUID();

    static Hospital pharmacy() {
        Hospital hospital = Hospital.builder().name("Pharmacie du Centre").code("PHC").build();
        hospital.setId(PROVIDER_ID);
        hospital.setFacilityType(FacilityType.PHARMACY);
        return hospital;
    }

    static Hospital hospital() {
        Hospital hospital = Hospital.builder().name("CHU").code("CHU").build();
        hospital.setId(HOSPITAL_ID);
        return hospital;
    }

    /**
     * The provider answer is exactly the answer of a miss at the same site:
     * the same exception, with the same message, as when the lookup finds
     * nothing.
     */
    static void assertAnsweredAsAMiss(ThrowingCallable call, Runnable makeItAMiss) {
        Throwable asProvider = catchThrowable(call);
        makeItAMiss.run();
        Throwable asMiss = catchThrowable(call);
        assertThat(asProvider).isInstanceOf(ResourceNotFoundException.class);
        assertThat(asMiss).isNotNull();
        assertThat(asProvider).hasSameClassAs(asMiss).hasMessage(asMiss.getMessage());
    }

    static Patient patient() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        return patient;
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("appointment booking (by id, by code, by name)")
    class Appointment {
        @Mock private HospitalRepository hospitalRepository;
        @InjectMocks private AppointmentServiceImpl service;

        @Test
        void bookingAtAProviderIsNotFound() {
            when(hospitalRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(pharmacy()));
            when(hospitalRepository.findByCodeIgnoreCase("PHC")).thenReturn(Optional.of(pharmacy()));
            when(hospitalRepository.findByNameIgnoreCase("Pharmacie du Centre")).thenReturn(Optional.of(pharmacy()));

            AppointmentRequestDTO byId = new AppointmentRequestDTO();
            byId.setHospitalId(PROVIDER_ID);
            AppointmentRequestDTO byCode = new AppointmentRequestDTO();
            byCode.setHospitalCode("PHC");
            AppointmentRequestDTO byName = new AppointmentRequestDTO();
            byName.setHospitalName("Pharmacie du Centre");

            assertAnsweredAsAMiss(() -> ReflectionTestUtils.invokeMethod(service, "resolveHospital", byId),
                () -> when(hospitalRepository.findById(PROVIDER_ID)).thenReturn(Optional.empty()));
            assertAnsweredAsAMiss(() -> ReflectionTestUtils.invokeMethod(service, "resolveHospital", byCode),
                () -> when(hospitalRepository.findByCodeIgnoreCase("PHC")).thenReturn(Optional.empty()));
            assertAnsweredAsAMiss(() -> ReflectionTestUtils.invokeMethod(service, "resolveHospital", byName),
                () -> when(hospitalRepository.findByNameIgnoreCase("Pharmacie du Centre")).thenReturn(Optional.empty()));
        }

        @Test
        void bookingAtAHospitalResolves() {
            when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital()));
            AppointmentRequestDTO byId = new AppointmentRequestDTO();
            byId.setHospitalId(HOSPITAL_ID);
            Hospital resolved = ReflectionTestUtils.invokeMethod(service, "resolveHospital", byId);
            assertThat(resolved).isNotNull();
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("general referral (referring and receiving hospital)")
    class GeneralReferral {
        @Mock private HospitalRepository hospitalRepository;
        @Mock private PatientRepository patientRepository;
        @Mock private StaffRepository staffRepository;
        @InjectMocks private GeneralReferralServiceImpl service;

        private GeneralReferralRequestDTO request(UUID hospitalId, UUID receivingHospitalId) {
            GeneralReferralRequestDTO request = new GeneralReferralRequestDTO();
            request.setPatientId(UUID.randomUUID());
            request.setHospitalId(hospitalId);
            request.setReferringProviderId(UUID.randomUUID());
            request.setReceivingHospitalId(receivingHospitalId);
            return request;
        }

        @BeforeEach
        void stubs() {
            when(patientRepository.findByIdUnscoped(any())).thenReturn(Optional.of(patient()));
            when(hospitalRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(pharmacy()));
            when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital()));
            when(staffRepository.findById(any())).thenReturn(Optional.of(new Staff()));
        }

        @Test
        void referringFromAProviderIsNotFound() {
            assertAnsweredAsAMiss(() -> service.createReferral(request(PROVIDER_ID, null)),
                () -> when(hospitalRepository.findById(PROVIDER_ID)).thenReturn(Optional.empty()));
        }

        @Test
        void referringToAProviderIsNotFound() {
            assertAnsweredAsAMiss(() -> service.createReferral(request(HOSPITAL_ID, PROVIDER_ID)),
                () -> when(hospitalRepository.findById(PROVIDER_ID)).thenReturn(Optional.empty()));
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("consultation")
    class Consultation {
        @Mock private HospitalRepository hospitalRepository;
        @Mock private PatientRepository patientRepository;
        @Mock private PatientHospitalRegistrationRepository registrationRepository;
        @Mock private RoleValidator roleValidator;
        @InjectMocks private ConsultationServiceImpl service;

        @Test
        void consultationAtAProviderIsNotFound() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            when(registrationRepository.existsByPatientIdAndHospitalId(any(), any())).thenReturn(true);
            when(patientRepository.findByIdUnscoped(any())).thenReturn(Optional.of(patient()));
            when(hospitalRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(pharmacy()));
            ConsultationRequestDTO request = new ConsultationRequestDTO();
            request.setPatientId(UUID.randomUUID());
            request.setHospitalId(PROVIDER_ID);

            assertAnsweredAsAMiss(() -> service.createConsultation(request, UUID.randomUUID()),
                () -> when(hospitalRepository.findById(PROVIDER_ID)).thenReturn(Optional.empty()));
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("OB-GYN referral")
    class ObgynReferral {
        @Mock private HospitalRepository hospitalRepository;
        @Mock private PatientRepository patientRepository;
        @InjectMocks private ObgynReferralServiceImpl service;

        @Test
        void referralAtAProviderIsNotFound() {
            when(patientRepository.findById(any())).thenReturn(Optional.of(patient()));
            when(hospitalRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(pharmacy()));
            ObgynReferralCreateRequestDTO request = new ObgynReferralCreateRequestDTO();
            request.setPatientId(UUID.randomUUID());
            request.setHospitalId(PROVIDER_ID);

            assertAnsweredAsAMiss(() -> service.createReferral(request, "midwife"),
                () -> when(hospitalRepository.findById(PROVIDER_ID)).thenReturn(Optional.empty()));
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("encounter (by id and by identifier)")
    class Encounter {
        @Mock private HospitalRepository hospitalRepository;
        @Mock private PatientRepository patientRepository;
        @Mock private StaffRepository staffRepository;
        @InjectMocks private EncounterServiceImpl service;

        @Test
        void encounterAtAProviderIsNotFound() {
            when(patientRepository.findByIdUnscoped(any())).thenReturn(Optional.of(patient()));
            when(staffRepository.findById(any())).thenReturn(Optional.of(new Staff()));
            when(hospitalRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(pharmacy()));
            EncounterRequestDTO request = new EncounterRequestDTO();
            request.setPatientId(UUID.randomUUID());
            request.setStaffId(UUID.randomUUID());
            request.setHospitalId(PROVIDER_ID);

            assertAnsweredAsAMiss(() -> ReflectionTestUtils.invokeMethod(service, "resolveEncounterResolution",
                    request, Locale.ENGLISH, null),
                () -> when(hospitalRepository.findById(PROVIDER_ID)).thenReturn(Optional.empty()));
        }

        @Test
        void encounterAtAProviderNamedByIdentifierIsNotFound() {
            when(hospitalRepository.findByNameOrCodeOrEmail("PHC")).thenReturn(Optional.of(pharmacy()));
            EncounterRequestDTO request = new EncounterRequestDTO();
            request.setHospitalIdentifier("PHC");

            assertAnsweredAsAMiss(() -> ReflectionTestUtils.invokeMethod(service, "resolveHospitalId",
                    request, Locale.ENGLISH),
                () -> when(hospitalRepository.findByNameOrCodeOrEmail("PHC")).thenReturn(Optional.empty()));
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("billing invoice (create and update)")
    class BillingInvoice {
        @Mock private HospitalRepository hospitalRepository;
        @Mock private PatientRepository patientRepository;
        @Mock private com.example.hms.repository.BillingInvoiceRepository invoiceRepository;
        @Mock private RoleValidator roleValidator;
        @InjectMocks private BillingInvoiceServiceImpl service;

        private BillingInvoiceRequestDTO request() {
            BillingInvoiceRequestDTO request = new BillingInvoiceRequestDTO();
            request.setPatientEmail("p@x.test");
            request.setHospitalName("Pharmacie du Centre");
            return request;
        }

        @BeforeEach
        void stubs() {
            when(patientRepository.findByUsernameOrEmail(anyString())).thenReturn(Optional.of(patient()));
            when(hospitalRepository.findByNameIgnoreCase("Pharmacie du Centre")).thenReturn(Optional.of(pharmacy()));
        }

        @Test
        void invoiceAtAProviderIsNotFound() {
            assertAnsweredAsAMiss(() -> service.createInvoice(request(), Locale.ENGLISH),
                () -> when(hospitalRepository.findByNameIgnoreCase("Pharmacie du Centre")).thenReturn(Optional.empty()));
        }

        @Test
        void invoiceMovedToAProviderIsNotFound() {
            com.example.hms.model.BillingInvoice existing = new com.example.hms.model.BillingInvoice();
            UUID invoiceId = UUID.randomUUID();
            when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(existing));
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);

            assertAnsweredAsAMiss(() -> service.updateInvoice(invoiceId, request(), Locale.ENGLISH),
                () -> when(hospitalRepository.findByNameIgnoreCase("Pharmacie du Centre")).thenReturn(Optional.empty()));
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("patient registration (by id and by name)")
    class Registration {
        @Mock private HospitalRepository hospitalRepository;
        @Mock private PatientRepository patientRepository;
        @InjectMocks private PatientHospitalRegistrationServiceImpl service;

        @Test
        void registrationAtAProviderIsNotFound() {
            when(patientRepository.findById(any())).thenReturn(Optional.of(patient()));
            when(hospitalRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(pharmacy()));
            when(hospitalRepository.findByName("Pharmacie du Centre")).thenReturn(Optional.of(pharmacy()));

            PatientHospitalRegistrationRequestDTO byId = new PatientHospitalRegistrationRequestDTO();
            byId.setPatientId(UUID.randomUUID());
            byId.setHospitalId(PROVIDER_ID);
            assertAnsweredAsAMiss(() -> service.registerPatient(byId),
                () -> when(hospitalRepository.findById(PROVIDER_ID)).thenReturn(Optional.empty()));

            PatientHospitalRegistrationRequestDTO byName = new PatientHospitalRegistrationRequestDTO();
            byName.setPatientId(UUID.randomUUID());
            byName.setHospitalName("Pharmacie du Centre");
            assertAnsweredAsAMiss(() -> service.registerPatient(byName),
                () -> when(hospitalRepository.findByName("Pharmacie du Centre")).thenReturn(Optional.empty()));
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("admin-register by hospital name")
    class AdminRegister {
        @Mock private HospitalRepository hospitalRepository;
        @InjectMocks private UserController controller;

        @Test
        void aProviderNamedByNameIsAnUnknownHospital() {
            when(hospitalRepository.findByName("Pharmacie du Centre")).thenReturn(Optional.of(pharmacy()));
            AdminSignupRequest request = new AdminSignupRequest();
            request.setHospitalName("Pharmacie du Centre");

            ResponseEntity<?> answer = ReflectionTestUtils.invokeMethod(controller, "resolveHospitalFromName", request);

            assertThat(answer).isNotNull();
            assertThat(answer.getStatusCode().value()).isEqualTo(400);
            assertThat(request.getHospitalId()).isNull();
        }

        @Test
        void aHospitalNamedByNameResolves() {
            when(hospitalRepository.findByName("CHU")).thenReturn(Optional.of(hospital()));
            AdminSignupRequest request = new AdminSignupRequest();
            request.setHospitalName("CHU");

            Object answer = ReflectionTestUtils.invokeMethod(controller, "resolveHospitalFromName", request);

            assertThat(answer).isNull();
            assertThat(request.getHospitalId()).isEqualTo(HOSPITAL_ID);
        }
    }
}
