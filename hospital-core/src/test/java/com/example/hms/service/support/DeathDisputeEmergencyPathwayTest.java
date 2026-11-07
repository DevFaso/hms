package com.example.hms.service.support;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AdmissionRequestDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationAdministrationRequestDTO;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestRequestDTO;
import com.example.hms.repository.DeathDisputeRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.TransfusionRequestRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.PrescriptionServiceImpl;
import com.example.hms.service.impl.AdmissionServiceImpl;
import com.example.hms.service.impl.NurseTaskServiceImpl;
import com.example.hms.service.impl.TransfusionServiceImpl;
import com.example.hms.service.pharmacy.DispenseServiceImpl;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.i18n.LocaleContextHolder;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The emergency pathway as one test: the real services of admit, prescribe, sign, dispense, MAR, transfusion request
 * and issue, on one patient who has died on record. Blocked, each refuses naming the death; under a dispute at this
 * hospital, none does, with no exemption of its own.
 *
 * <p>Its persistence is mocked: past the guard a step may stop at a normal check these mocks do not satisfy, which is
 * what "normal checks continue" means and is all this asserts. A walk on a running system is QA's.
 */
@DisplayName("The emergency pathway under a disputed death")
class DeathDisputeEmergencyPathwayTest {

    private static final String DEATH_REFUSAL = "recorded as deceased";

    private final Map<Class<?>, Object> mocks = new HashMap<>();
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID doctorId = UUID.randomUUID();
    private Patient patient;
    private Hospital hospital;
    private Staff staff;
    private DeathDisputeRepository disputes;
    private Prescription order;
    private TransfusionRequest bloodRequest;

    @SuppressWarnings("unchecked")
    private <T> T mockOf(Class<T> type) {
        return (T) mocks.computeIfAbsent(type, Mockito::mock);
    }

    /** The service's real code, on a shared mock of each thing it is built from. */
    @SuppressWarnings("unchecked")
    private <T> T real(Class<T> service) throws ReflectiveOperationException {
        Constructor<?> constructor = Arrays.stream(service.getDeclaredConstructors())
            .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        constructor.setAccessible(true);
        Object[] args = Arrays.stream(constructor.getParameterTypes()).map(this::argument).toArray();
        return (T) constructor.newInstance(args);
    }

    private Object argument(Class<?> type) {
        return type.isInterface() || !java.lang.reflect.Modifier.isFinal(type.getModifiers()) ? mockOf(type) : null;
    }

    @BeforeEach
    void setUp() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId).permittedHospitalIds(Set.of(hospitalId)).build());
        hospital = new Hospital();
        hospital.setId(hospitalId);
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setDeceasedAt(LocalDateTime.now().minusDays(1));
        User doctor = new User();
        doctor.setId(doctorId);
        staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(doctor);
        staff.setHospital(hospital);

        disputes = Mockito.mock(DeathDisputeRepository.class);
        new DeathCareEligibility(disputes).afterPropertiesSet();

        when(mockOf(RoleValidator.class).requireActiveHospitalId()).thenReturn(hospitalId);
        when(mockOf(RoleValidator.class).getCurrentUserId()).thenReturn(doctorId);
        when(mockOf(PatientRepository.class).findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        when(mockOf(HospitalRepository.class).findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(mockOf(StaffRepository.class).findById(staff.getId())).thenReturn(Optional.of(staff));
        when(mockOf(PatientHospitalRegistrationRepository.class).existsByPatientIdAndHospitalId(patient.getId(), hospitalId))
            .thenReturn(true);
        when(mockOf(PatientChartAccess.class).require(patient.getId(), hospitalId)).thenReturn(patient);

        order = new Prescription();
        order.setId(UUID.randomUUID());
        order.setPatient(patient);
        order.setHospital(hospital);
        order.setStaff(staff);
        order.setStatus(PrescriptionStatus.DRAFT);
        order.setMedicationName("Morphine");
        when(mockOf(PrescriptionRepository.class).findById(order.getId())).thenReturn(Optional.of(order));
        when(mockOf(PrescriptionRepository.class).findByIdAndHospitalIdForUpdate(order.getId(), hospitalId))
            .thenReturn(Optional.of(order));

        bloodRequest = TransfusionRequest.builder().patient(patient).hospital(hospital).build();
        bloodRequest.setId(UUID.randomUUID());
        when(mockOf(TransfusionRequestRepository.class).findById(bloodRequest.getId())).thenReturn(Optional.of(bloodRequest));
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
        DeceasedPatientGuard.useDisputes(null);
        OrderReviewHoldGuard.useHolds(null);
    }

    /** Runs a step and tells whether the death refused it. */
    private static boolean refusedByTheDeath(ThrowingStep step) {
        try {
            step.run();
            return false;
        } catch (FieldBusinessException e) {
            return String.valueOf(e.getMessage()).contains(DEATH_REFUSAL);
        } catch (Exception | AssertionError e) {
            return false;
        }
    }

    @FunctionalInterface
    private interface ThrowingStep {
        void run() throws Exception;
    }

    private Map<String, ThrowingStep> thePathway() throws ReflectiveOperationException {
        AdmissionServiceImpl admissions = real(AdmissionServiceImpl.class);
        PrescriptionServiceImpl prescriptions = real(PrescriptionServiceImpl.class);
        DispenseServiceImpl dispenses = real(DispenseServiceImpl.class);
        NurseTaskServiceImpl nursing = real(NurseTaskServiceImpl.class);
        TransfusionServiceImpl transfusions = real(TransfusionServiceImpl.class);

        Encounter visit = new Encounter();
        visit.setId(UUID.randomUUID());
        visit.setPatient(patient);
        visit.setStaff(staff);
        visit.setHospital(hospital);
        visit.setEncounterType(EncounterType.CONSULTATION);
        visit.setStatus(EncounterStatus.IN_PROGRESS);
        when(mockOf(EncounterRepository.class).findById(visit.getId())).thenReturn(Optional.of(visit));

        AdmissionRequestDTO admit = new AdmissionRequestDTO();
        admit.setPatientId(patient.getId());
        admit.setHospitalId(hospitalId);
        admit.setAdmittingProviderId(staff.getId());

        PrescriptionRequestDTO prescribe = new PrescriptionRequestDTO();
        prescribe.setPatientId(patient.getId());
        prescribe.setStaffId(staff.getId());
        prescribe.setEncounterId(visit.getId());
        prescribe.setMedicationName("Morphine");

        DispenseRequestDTO fill = new DispenseRequestDTO();
        fill.setPrescriptionId(order.getId());
        fill.setPatientId(patient.getId());
        fill.setQuantityRequested(BigDecimal.ONE);
        fill.setQuantityDispensed(BigDecimal.ONE);
        fill.setUnit("tablet");

        NurseMedicationAdministrationRequestDTO dose = new NurseMedicationAdministrationRequestDTO();
        dose.setStatus("GIVEN");

        TransfusionRequestRequestDTO blood = new TransfusionRequestRequestDTO();
        blood.setPatientId(patient.getId());

        Map<String, ThrowingStep> steps = new LinkedHashMap<>();
        steps.put("admit", () -> admissions.admitPatient(admit));
        steps.put("prescribe", () -> prescriptions.createPrescription(prescribe, Locale.ENGLISH));
        steps.put("sign", () -> prescriptions.signPrescription(order.getId(), null, Locale.ENGLISH));
        steps.put("dispense", () -> {
            order.setStatus(PrescriptionStatus.SIGNED);
            try {
                dispenses.createDispenseTransactionally(fill);
            } finally {
                order.setStatus(PrescriptionStatus.DRAFT);
            }
        });
        steps.put("MAR", () -> nursing.recordMedicationAdministration(order.getId(), doctorId, hospitalId, dose));
        steps.put("transfusion request", () -> transfusions.createRequest(blood));
        steps.put("transfusion issue", () -> transfusions.issueUnit(bloodRequest.getId(), UUID.randomUUID()));
        return steps;
    }

    @Test
    @DisplayName("blocked: every step refuses naming the death; disputed here: none does; disputed elsewhere: all do")
    void theWholePathway() throws ReflectiveOperationException {
        Map<String, ThrowingStep> steps = thePathway();

        steps.forEach((name, step) -> assertThat(refusedByTheDeath(step)).as("blocked: " + name).isTrue());

        when(disputes.existsOpen(patient.getId(), UUID.randomUUID())).thenReturn(true);
        steps.forEach((name, step) -> assertThat(refusedByTheDeath(step)).as("disputed elsewhere: " + name).isTrue());

        when(disputes.existsOpen(patient.getId(), hospitalId)).thenReturn(true);
        steps.forEach((name, step) -> assertThat(refusedByTheDeath(step)).as("disputed here: " + name).isFalse());

        when(disputes.existsOpen(patient.getId(), hospitalId)).thenReturn(false);
        steps.forEach((name, step) -> assertThat(refusedByTheDeath(step)).as("upheld: " + name).isTrue());
    }

    @Test
    @DisplayName("under the dispute, an existing morphine and blood request are refused naming the review until reconfirmed")
    void existingOrdersAreHeldUntilReconfirmed() throws ReflectiveOperationException {
        when(disputes.existsOpen(patient.getId(), hospitalId)).thenReturn(true);
        java.util.Set<UUID> held = new java.util.HashSet<>(java.util.List.of(order.getId(), bloodRequest.getId()));
        OrderReviewHoldGuard.useHolds((type, id) -> held.contains(id));
        Map<String, ThrowingStep> steps = thePathway();
        steps.remove("admit");
        steps.remove("prescribe");
        steps.remove("transfusion request");

        steps.forEach((name, step) -> assertThat(refusedByTheHold(step)).as("held: " + name).isTrue());

        held.clear();
        steps.forEach((name, step) -> assertThat(refusedByTheHold(step)).as("reconfirmed: " + name).isFalse());
    }

    @Test
    @DisplayName("what is new under the dispute has no hold: admit, prescribe and a new blood request")
    void newOrdersAreNotHeld() throws ReflectiveOperationException {
        when(disputes.existsOpen(patient.getId(), hospitalId)).thenReturn(true);
        OrderReviewHoldGuard.useHolds((type, id) -> id.equals(order.getId()) || id.equals(bloodRequest.getId()));
        Map<String, ThrowingStep> steps = thePathway();

        for (String step : java.util.List.of("admit", "prescribe", "transfusion request")) {
            assertThat(refusedByTheHold(steps.get(step))).as(step).isFalse();
        }
    }

    private static boolean refusedByTheHold(ThrowingStep step) {
        try {
            step.run();
            return false;
        } catch (FieldBusinessException e) {
            return String.valueOf(e.getMessage()).contains("review");
        } catch (Exception | AssertionError e) {
            return false;
        }
    }

    @Test
    @DisplayName("reopening care restarts nothing: a cancelled order stays cancelled and takes no signature or dose")
    void aCancelledOrderStaysCancelled() throws ReflectiveOperationException {
        when(disputes.existsOpen(patient.getId(), hospitalId)).thenReturn(true);
        order.setStatus(PrescriptionStatus.CANCELLED);
        PrescriptionServiceImpl prescriptions = real(PrescriptionServiceImpl.class);
        NurseTaskServiceImpl nursing = real(NurseTaskServiceImpl.class);
        NurseMedicationAdministrationRequestDTO dose = new NurseMedicationAdministrationRequestDTO();
        dose.setStatus("GIVEN");
        UUID orderId = order.getId();

        // A refusal of the order's own state: not the death's, and not a stub falling over.
        assertThatThrownBy(() -> prescriptions.signPrescription(orderId, null, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.BusinessException.class).hasMessageNotContaining(DEATH_REFUSAL);
        assertThatThrownBy(() -> nursing.recordMedicationAdministration(orderId, doctorId, hospitalId, dose))
            .isNotInstanceOf(NullPointerException.class).hasMessageNotContaining(DEATH_REFUSAL);

        assertThat(order.getStatus()).isEqualTo(PrescriptionStatus.CANCELLED);
        Mockito.verify(mockOf(PrescriptionRepository.class), Mockito.never()).save(any());
    }
}
