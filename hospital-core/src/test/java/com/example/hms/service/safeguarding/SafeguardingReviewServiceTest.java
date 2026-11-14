package com.example.hms.service.safeguarding;

import com.example.hms.enums.SafeguardingReviewStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.safeguarding.SafeguardingReview;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.SafeguardingReviewRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * r53: who reads and who records a safeguarding review. The treating team and the administrators of the review's own
 * hospital read it; reception, billing, the patient (and so every proxy acting through her), a super-admin with no
 * role there and another hospital's staff never do.
 */
@DisplayName("r53: a safeguarding review is read by its hospital's treating team and administrators only")
class SafeguardingReviewServiceTest {

    private final SafeguardingReviewRepository reviews = mock(SafeguardingReviewRepository.class);
    private final PatientRepository patients = mock(PatientRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final InBasketService inBasket = mock(InBasketService.class);
    private final SafeguardingReviewService service =
        new SafeguardingReviewService(reviews, patients, users, roles, inBasket);

    private final UUID caller = UUID.randomUUID();
    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private SafeguardingReview reviewAtA;

    @BeforeEach
    void setUp() {
        when(roles.getCurrentUserId()).thenReturn(caller);
        when(roles.requireActiveHospitalId()).thenReturn(hospitalA);
        when(patients.findById(any())).thenReturn(Optional.empty());
        when(users.findById(any())).thenReturn(Optional.empty());
        reviewAtA = SafeguardingReview.builder().hospitalId(hospitalA).patientId(patientId)
            .triggerCode(SafeguardingReview.TRIGGER_MINOR_LABOUR).sourceType(SafeguardingReview.SOURCE_ADMISSION)
            .sourceId(UUID.randomUUID()).patientAgeYears(10).ageThresholdYears(13).raisedAt(LocalDateTime.now())
            .status(SafeguardingReviewStatus.OPEN).build();
        reviewAtA.setId(UUID.randomUUID());
        when(reviews.findById(reviewAtA.getId())).thenReturn(Optional.of(reviewAtA));
        when(reviews.findByPatientIdAndHospitalIdOrderByRaisedAtDesc(patientId, hospitalA)).thenReturn(List.of(reviewAtA));
        when(reviews.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void eachRoleOfTheTreatingTeamAndTheHospitalAdministratorReadsIt() {
        for (Runnable grant : List.<Runnable>of(
                () -> when(roles.isDoctor(caller, hospitalA)).thenReturn(true),
                () -> when(roles.isSurgeon(caller, hospitalA)).thenReturn(true),
                () -> when(roles.isMidwife(caller, hospitalA)).thenReturn(true),
                () -> when(roles.isNurse(caller, hospitalA)).thenReturn(true),
                () -> when(roles.isHospitalAdmin(caller, hospitalA)).thenReturn(true))) {
            org.mockito.Mockito.reset(roles);
            when(roles.getCurrentUserId()).thenReturn(caller);
            when(roles.requireActiveHospitalId()).thenReturn(hospitalA);
            grant.run();
            assertThat(service.listForPatient(patientId)).hasSize(1)
                .allSatisfy(dto -> assertThat(dto.isProvisionalRule()).isTrue());
        }
    }

    /** Reception, billing, a patient or her proxy, the lab or the pharmacy: none holds a treating role here. */
    @Test
    void anyOtherRoleIsRefusedAndNothingIsRead() {
        assertThatThrownBy(() -> service.listForPatient(patientId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.listForHospital(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.recordReview(reviewAtA.getId(), "note")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(reviews);
    }

    /** A doctor of hospital A acting at hospital B (the caller's other hospital) reads nothing of A's. */
    @Test
    void anotherHospitalNeverReadsOrRecordsIt() {
        when(roles.requireActiveHospitalId()).thenReturn(hospitalB);
        when(roles.isDoctor(caller, hospitalB)).thenReturn(true);
        assertThat(service.listForPatient(patientId)).isEmpty();
        assertThat(service.listForHospital(SafeguardingReviewStatus.OPEN)).isEmpty();
        verify(reviews, never()).findByPatientIdAndHospitalIdOrderByRaisedAtDesc(patientId, hospitalA);
        assertThatThrownBy(() -> service.recordReview(reviewAtA.getId(), "note"))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(reviewAtA.getStatus()).isEqualTo(SafeguardingReviewStatus.OPEN);
    }

    /** A super-admin with no assignment at the hospital, or an unscoped (global) one, reads nothing. */
    @Test
    void anUnscopedSuperAdminIsRefused() {
        when(roles.requireActiveHospitalId()).thenReturn(null);
        assertThatThrownBy(() -> service.listForHospital(null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aDoctorOfTheHospitalRecordsTheReviewAndItsTasksClear() {
        when(roles.isDoctor(caller, hospitalA)).thenReturn(true);
        var dto = service.recordReview(reviewAtA.getId(), "  Seen with the safeguarding lead.  ");
        assertThat(dto.getStatus()).isEqualTo(SafeguardingReviewStatus.REVIEWED);
        assertThat(reviewAtA.getReviewNote()).isEqualTo("Seen with the safeguarding lead.");
        assertThat(reviewAtA.getReviewedByUserId()).isEqualTo(caller);
        verify(inBasket).removeAllItemsForReference(reviewAtA.getId(), "SAFEGUARDING_REVIEW");
        verify(inBasket).removeAllItemsForReference(reviewAtA.getId(), "SENIOR_OBSTETRIC_REVIEW");
    }

    @Test
    void aNurseMidwifeOrAdministratorReadsButDoesNotRecord() {
        when(roles.isNurse(caller, hospitalA)).thenReturn(true);
        when(roles.isMidwife(caller, hospitalA)).thenReturn(true);
        when(roles.isHospitalAdmin(caller, hospitalA)).thenReturn(true);
        assertThatThrownBy(() -> service.recordReview(reviewAtA.getId(), "note"))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(reviewAtA.getStatus()).isEqualTo(SafeguardingReviewStatus.OPEN);
    }

    @Test
    void aRecordedReviewIsNotRecordedTwice() {
        when(roles.isDoctor(caller, hospitalA)).thenReturn(true);
        service.recordReview(reviewAtA.getId(), "first");
        assertThatThrownBy(() -> service.recordReview(reviewAtA.getId(), "second"))
            .isInstanceOf(com.example.hms.exception.ConflictException.class);
        assertThat(reviewAtA.getReviewNote()).isEqualTo("first");
    }
}
