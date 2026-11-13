package com.example.hms.service.maternity;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.DeliveryMode;
import com.example.hms.enums.InfantSex;
import com.example.hms.enums.ProxyRelationship;
import com.example.hms.enums.ProxyStatus;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProxy;
import com.example.hms.model.User;
import com.example.hms.model.labor.DeliveryNewborn;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.clinical.labor.DeliveryNewbornDTO;
import com.example.hms.repository.DeliveryNewbornRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProxyRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.PatientAccountRegistrationService;
import com.example.hms.service.support.UserAccountAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D1592: a recorded live birth registers each baby as its own patient, once. */
class NewbornRegistrationServiceTest {

    private final DeliveryNewbornRepository links = mock(DeliveryNewbornRepository.class);
    private final PatientAccountRegistrationService registration = mock(PatientAccountRegistrationService.class);
    private final PatientRepository patients = mock(PatientRepository.class);
    private final PatientHospitalRegistrationRepository registrations = mock(PatientHospitalRegistrationRepository.class);
    private final PatientProxyRepository proxies = mock(PatientProxyRepository.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final com.example.hms.repository.DeliveryRecordRepository deliveries =
        mock(com.example.hms.repository.DeliveryRecordRepository.class);
    private final NewbornRegistrationService service = new NewbornRegistrationService(links, deliveries, registration,
        patients, registrations, proxies, TestMessageSources.bundles(), audit);

    private final List<DeliveryNewborn> saved = new ArrayList<>();
    private Hospital hospital;
    private Patient mother;
    private User motherAccount;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("CHU A");
        motherAccount = new User();
        motherAccount.setId(UUID.randomUUID());
        mother = new Patient();
        mother.setId(UUID.randomUUID());
        mother.setFirstName("Awa");
        mother.setLastName("Traore");
        mother.setPhoneNumberPrimary("+22670000000");
        mother.setCity("Ouagadougou");
        mother.setUser(motherAccount);
        when(links.findByDeliveryRecord_IdOrderByBirthOrderAsc(any())).thenAnswer(inv -> List.copyOf(saved));
        when(links.save(any(DeliveryNewborn.class))).thenAnswer(inv -> {
            DeliveryNewborn row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
                saved.add(row);
            }
            return row;
        });
        when(registration.registerNewborn(any(), any(), any(), any())).thenAnswer(inv -> {
            PatientRequestDTO request = inv.getArgument(1);
            PatientResponseDTO created = new PatientResponseDTO();
            created.setId(UUID.randomUUID());
            Patient baby = new Patient();
            baby.setId(created.getId());
            baby.setFirstName(request.getFirstName());
            baby.setLastName(request.getLastName());
            when(patients.getReferenceById(created.getId())).thenReturn(baby);
            return created;
        });
        when(proxies.findByGrantorPatient_IdAndProxyUser_IdAndStatus(any(), any(), any())).thenReturn(Optional.empty());
    }

    private DeliveryRecord delivery(boolean liveBirth, int infants, InfantSex sex) {
        DeliveryRecord record = DeliveryRecord.builder()
            .patient(mother)
            .hospital(hospital)
            .birthDateTime(LocalDateTime.of(2026, 11, 12, 3, 40))
            .deliveryMode(DeliveryMode.SPONTANEOUS_VAGINAL)
            .liveBirth(liveBirth)
            .numberOfInfants(infants)
            .infantSex(sex)
            .newbornRegistrationPending(liveBirth)
            .build();
        record.setId(UUID.randomUUID());
        return record;
    }

    @Test
    @DisplayName("a single live birth registers one baby at the delivering hospital, linked to the mother")
    void singleBirth() {
        DeliveryRecord delivery = delivery(true, 1, InfantSex.FEMALE);
        UUID recorder = UUID.randomUUID();

        List<DeliveryNewbornDTO> babies = service.registerLiveBorn(delivery, recorder);

        ArgumentCaptor<AdminSignupRequest> account = ArgumentCaptor.forClass(AdminSignupRequest.class);
        ArgumentCaptor<PatientRequestDTO> record = ArgumentCaptor.forClass(PatientRequestDTO.class);
        verify(registration).registerNewborn(account.capture(), record.capture(), eq(hospital.getId()), any());
        PatientRequestDTO baby = record.getValue();
        assertThat(baby.getFirstName()).isEqualTo("Bébé");
        assertThat(baby.getLastName()).isEqualTo("Traore");
        assertThat(baby.getDateOfBirth()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(baby.getGender()).isEqualTo("FEMALE");
        assertThat(baby.getPhoneNumberPrimary()).isEqualTo("+22670000000");
        assertThat(baby.getEmergencyContactName()).isEqualTo("Awa Traore");
        assertThat(baby.getEmergencyContactRelationship()).isEqualTo("Mère");
        // The account has no login channel of its own: no e-mail, no phone.
        assertThat(account.getValue().getEmail()).isNull();
        assertThat(account.getValue().getPhoneNumber()).isNull();

        assertThat(babies).hasSize(1);
        assertThat(babies.get(0).getBirthOrder()).isEqualTo(1);
        assertThat(delivery.isNewbornRegistrationPending()).as("the pending flag is cleared").isFalse();
        assertThat(saved).singleElement().satisfies(link -> {
            assertThat(link.getMotherPatient()).isSameAs(mother);
            assertThat(link.getHospital()).isSameAs(hospital);
            assertThat(link.getDeliveryRecord()).isSameAs(delivery);
        });

        ArgumentCaptor<PatientProxy> proxy = ArgumentCaptor.forClass(PatientProxy.class);
        verify(proxies).save(proxy.capture());
        assertThat(proxy.getValue().getProxyUser()).isSameAs(motherAccount);
        assertThat(proxy.getValue().getRelationship()).isEqualTo(ProxyRelationship.PARENT);
        assertThat(proxy.getValue().getStatus()).isEqualTo(ProxyStatus.ACTIVE);

        ArgumentCaptor<AuditEventRequestDTO> event = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(AuditEventType.PATIENT_CREATE);
        assertThat(event.getValue().getHospitalId()).isEqualTo(hospital.getId());
        assertThat(event.getValue().getUserId()).isEqualTo(recorder);
    }

    @Test
    @DisplayName("twins register two babies, numbered; the recorded sex is the first baby's only")
    void twins() {
        List<DeliveryNewbornDTO> babies = service.registerLiveBorn(delivery(true, 2, InfantSex.MALE), null);

        ArgumentCaptor<PatientRequestDTO> record = ArgumentCaptor.forClass(PatientRequestDTO.class);
        verify(registration, times(2)).registerNewborn(any(), record.capture(), eq(hospital.getId()), any());
        assertThat(record.getAllValues()).extracting(PatientRequestDTO::getFirstName).containsExactly("Bébé 1", "Bébé 2");
        assertThat(record.getAllValues()).extracting(PatientRequestDTO::getGender).containsExactly("MALE", null);
        assertThat(babies).extracting(DeliveryNewbornDTO::getBirthOrder).containsExactly(1, 2);
    }

    @Test
    @DisplayName("an undetermined sex stays unknown")
    void undeterminedSexStaysUnknown() {
        service.registerLiveBorn(delivery(true, 1, InfantSex.UNDETERMINED), null);

        ArgumentCaptor<PatientRequestDTO> record = ArgumentCaptor.forClass(PatientRequestDTO.class);
        verify(registration).registerNewborn(any(), record.capture(), any(), any());
        assertThat(record.getValue().getGender()).isNull();
    }

    @Test
    @DisplayName("a stillbirth registers no patient")
    void stillbirth() {
        assertThat(service.registerLiveBorn(delivery(false, 1, InfantSex.FEMALE), null)).isEmpty();

        verify(registration, never()).registerNewborn(any(), any(), any(), any());
        verify(proxies, never()).save(any());
    }

    @Test
    @DisplayName("a second pass over the same delivery registers no one again")
    void reSaveIsIdempotent() {
        DeliveryRecord delivery = delivery(true, 2, InfantSex.FEMALE);
        service.registerLiveBorn(delivery, null);
        List<DeliveryNewbornDTO> again = service.registerLiveBorn(delivery, null);

        verify(registration, times(2)).registerNewborn(any(), any(), any(), any());
        assertThat(again).hasSize(2);
        assertThat(saved).hasSize(2);
    }

    @Test
    @DisplayName("a lower live-birth count flags the extra baby for review and deletes no one")
    void countLoweredIsFlaggedNotDeleted() {
        DeliveryRecord delivery = delivery(true, 2, InfantSex.FEMALE);
        service.registerLiveBorn(delivery, null);
        delivery.setNumberOfInfants(1);

        List<DeliveryNewbornDTO> after = service.registerLiveBorn(delivery, null);

        assertThat(after).hasSize(2);
        assertThat(after).extracting(DeliveryNewbornDTO::isNeedsReview).containsExactly(false, true);
        assertThat(saved.get(1).getReviewReason()).isEqualTo(NewbornRegistrationService.REVIEW_COUNT_LOWERED);
        verify(links, never()).delete(any());
        verify(links, never()).deleteAll(any());

        delivery.setLiveBirth(false);
        service.registerLiveBorn(delivery, null);
        assertThat(saved.get(0).isNeedsReview()).isTrue();
        assertThat(saved.get(0).getReviewReason()).isEqualTo(NewbornRegistrationService.REVIEW_NOT_LIVE_BIRTH);
        verify(registration, times(2)).registerNewborn(any(), any(), any(), any());
    }

    @Test
    @DisplayName("tenancy: the baby's account grant is PATIENT at the delivering hospital only")
    void grantIsTheDeliveringHospitalOnly() {
        UUID delivering = UUID.randomUUID();
        UserAccountAccess.Grant grant = UserAccountAccess.Grant.newbornPatientAt(delivering);

        assertThat(grant.requireAt(delivering)).isEqualTo(delivering);
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> grant.requireAt(other)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> grant.requireAt(null)).isInstanceOf(AccessDeniedException.class);
    }
}
