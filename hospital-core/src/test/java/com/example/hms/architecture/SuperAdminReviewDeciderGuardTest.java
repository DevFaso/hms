package com.example.hms.architecture;

import com.example.hms.controller.MortalityController;
import com.example.hms.mapper.MortalityMapper;
import com.example.hms.model.DeathDispute;
import com.example.hms.model.DeathRecord;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.mortality.DeathDisputeOutcomeDTO;
import com.example.hms.payload.dto.mortality.DeathRecordEnteredInErrorDTO;
import com.example.hms.repository.DeathDisputeRepository;
import com.example.hms.repository.DeathRecordRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.impl.DeathDisputeServiceImpl;
import com.example.hms.service.impl.MortalityServiceImpl;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA (class: a super-admin with a hospital selected was refused the mortality review, "Impossible de charger le
 * registre de mortalité", 403 on {@code GET /mortality/disputes}). The CORRECTOR endpoints admit SUPER_ADMIN, but
 * their services asked {@code CallerIdentity.isGlobalSuperAdmin()}, true only in global view, where the review list
 * has no hospital to read. Every CORRECTOR endpoint of {@link MortalityController} is run here, against its real
 * service, as a verified super-admin pinned to the record's hospital with no hospital-admin role there: it must not
 * be refused. The same call by a pinned caller who is not a super-admin must be refused, which proves each scenario
 * reaches the decider. A new CORRECTOR endpoint fails {@link #everyCorrectorEndpointHasAScenario()} until it gets
 * a scenario here.
 */
@DisplayName("A super-admin with a hospital selected may do what every mortality CORRECTOR endpoint offers there")
class SuperAdminReviewDeciderGuardTest {

    private static final Set<String> SCENARIOS = Set.of("markEnteredInError", "openDisputes", "upholdDeath");

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final RoleValidator roles = mock(RoleValidator.class);
    private final DeathRecordRepository records = mock(DeathRecordRepository.class);
    private final DeathDisputeRepository disputes = mock(DeathDisputeRepository.class);
    private final PatientRepository patients = mock(PatientRepository.class);
    private DeathRecord record;
    private DeathDispute dispute;

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
        com.example.hms.utility.MessageUtil.setMessageSource(null);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void everyCorrectorEndpointHasAScenario() throws ReflectiveOperationException {
        assertThat(correctorEndpoints())
            .as("every CORRECTOR endpoint of MortalityController is run below as a pinned super-admin")
            .isEqualTo(new TreeSet<>(SCENARIOS));
    }

    @Test
    void aPinnedSuperAdminIsNeverRefused() throws ReflectiveOperationException {
        for (String endpoint : correctorEndpoints()) {
            fixture();
            HospitalContextHolder.setContext(pinned(true));
            assertThatCode(() -> run(endpoint)).as(endpoint).doesNotThrowAnyException();
            HospitalContextHolder.clear();
        }
    }

    @Test
    void aPinnedCallerWhoIsNotASuperAdminIsStillRefused() throws ReflectiveOperationException {
        for (String endpoint : correctorEndpoints()) {
            fixture();
            HospitalContextHolder.setContext(pinned(false));
            assertThatThrownBy(() -> run(endpoint)).as(endpoint).isInstanceOf(AccessDeniedException.class);
            HospitalContextHolder.clear();
        }
    }

    /** The controller methods whose {@code @PreAuthorize} is the CORRECTOR rule, read from the controller itself. */
    private static Set<String> correctorEndpoints() throws ReflectiveOperationException {
        Field field = MortalityController.class.getDeclaredField("CORRECTOR");
        field.setAccessible(true);
        String corrector = (String) field.get(null);
        Set<String> names = new TreeSet<>();
        for (Method method : MortalityController.class.getDeclaredMethods()) {
            PreAuthorize rule = method.getAnnotation(PreAuthorize.class);
            if (rule != null && rule.value().equals(corrector)) {
                names.add(method.getName());
            }
        }
        assertThat(names).as("the CORRECTOR rule is found on the controller").isNotEmpty();
        return names;
    }

    private HospitalContext pinned(boolean superAdmin) {
        return HospitalContext.builder()
            .principalUserId(actorId)
            .superAdmin(superAdmin)
            .activeHospitalId(hospitalId)
            .headerOverridden(true)
            .build();
    }

    private void fixture() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        org.mockito.Mockito.reset(roles, records, disputes, patients);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setDeceasedAt(LocalDateTime.now().minusDays(1));
        record = DeathRecord.builder().patient(patient).hospital(hospital).diedAt(patient.getDeceasedAt())
            .immediateCause("x").version(1L).build();
        record.setId(UUID.randomUUID());
        dispute = DeathDispute.builder().deathRecordId(record.getId()).deathRecordVersion(1L)
            .patientId(patient.getId()).hospitalId(hospitalId).disputedById(UUID.randomUUID())
            .disputedAt(LocalDateTime.now().minusHours(2)).reason("Seen alive").assessedAlive(true)
            .identityVerified(true).build();
        dispute.setId(UUID.randomUUID());

        when(roles.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roles.getCurrentUserId()).thenReturn(actorId);
        when(roles.isHospitalAdmin(any(), any())).thenReturn(false);
        when(records.findById(record.getId())).thenReturn(Optional.of(record));
        when(records.save(any())).thenAnswer(call -> call.getArgument(0));
        when(patients.save(any())).thenAnswer(call -> call.getArgument(0));
        when(patients.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        when(disputes.lockById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(disputes.findOpenForReview(hospitalId)).thenReturn(List.of(dispute));
        when(disputes.lockOpenForRecord(record.getId())).thenReturn(List.of());
    }

    private void run(String endpoint) throws ReflectiveOperationException {
        switch (endpoint) {
            case "markEnteredInError" -> mortalityService().markEnteredInError(record.getId(),
                DeathRecordEnteredInErrorDTO.builder().reason("Wrong patient").build());
            case "openDisputes" -> assertThat(disputeService().openForReview()).hasSize(1);
            case "upholdDeath" -> disputeService().uphold(dispute.getId(),
                DeathDisputeOutcomeDTO.builder().reason("Family confirmed").build());
            default -> fail("No scenario for the CORRECTOR endpoint " + endpoint + ": add one to this guard");
        }
    }

    private MortalityServiceImpl mortalityService() throws ReflectiveOperationException {
        return (MortalityServiceImpl) construct(MortalityServiceImpl.class.getDeclaredConstructors()[0]);
    }

    private DeathDisputeServiceImpl disputeService() throws ReflectiveOperationException {
        return (DeathDisputeServiceImpl) construct(DeathDisputeServiceImpl.class.getDeclaredConstructors()[0]);
    }

    /** The service with this fixture's role validator and repositories, and a mock for every other dependency. */
    private Object construct(Constructor<?> constructor) throws ReflectiveOperationException {
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == Clock.class) {
                args[i] = Clock.systemDefaultZone();
            } else if (type == MortalityMapper.class) {
                args[i] = new MortalityMapper();
            } else if (type == RoleValidator.class) {
                args[i] = roles;
            } else if (type == DeathRecordRepository.class) {
                args[i] = records;
            } else if (type == DeathDisputeRepository.class) {
                args[i] = disputes;
            } else if (type == PatientRepository.class) {
                args[i] = patients;
            } else {
                args[i] = mock(type);
            }
        }
        return constructor.newInstance(args);
    }
}
