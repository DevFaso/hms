package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.EducationCategory;
import com.example.hms.enums.EducationComprehensionStatus;
import com.example.hms.enums.EducationResourceType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientEducationQuestionMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.education.EducationResource;
import com.example.hms.model.education.PatientEducationProgress;
import com.example.hms.model.education.PatientEducationQuestion;
import com.example.hms.payload.dto.portal.PatientEducationItemDTO;
import com.example.hms.payload.dto.portal.PatientEducationProgressUpdateDTO;
import com.example.hms.payload.dto.portal.PatientEducationQuestionSubmitDTO;
import com.example.hms.repository.EducationResourceRepository;
import com.example.hms.repository.PatientEducationProgressRepository;
import com.example.hms.repository.PatientEducationQuestionRepository;
import com.example.hms.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Patient-facing education delivery: assignment-gated reads, derived
 * comprehension status, and the resource roll-up counters.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({"java:S100", "java:S1192"})
class PatientPortalServiceImplEducationTest {

    @Mock private PatientRepository patientRepository;
    @Mock private ControllerAuthUtils authUtils;
    @Mock private PatientEducationProgressRepository educationProgressRepository;
    @Mock private PatientEducationQuestionRepository educationQuestionRepository;
    @Mock private EducationResourceRepository educationResourceRepository;
    @Mock private PatientEducationQuestionMapper educationQuestionMapper;

    @InjectMocks
    private PatientPortalServiceImpl service;

    @Mock private Authentication auth;

    private UUID userId;
    private UUID patientId;
    private UUID resourceId;
    private Patient patient;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        resourceId = UUID.randomUUID();

        User user = new User();
        user.setId(userId);

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Awa");
        patient.setLastName("Diallo");
        patient.setUser(user);

        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
    }

    private EducationResource resource(boolean active) {
        EducationResource r = new EducationResource();
        r.setId(resourceId);
        r.setTitle("Warning signs in pregnancy");
        r.setDescription("What to watch for");
        r.setResourceType(EducationResourceType.ARTICLE);
        r.setCategory(EducationCategory.WARNING_SIGNS);
        r.setIsActive(active);
        r.setIsWarningSignContent(true);
        r.setEstimatedDuration(8);
        r.setCompletionCount(0L);
        r.setRatingCount(0L);
        return r;
    }

    private PatientEducationProgress progress(int percent, LocalDateTime completedAt) {
        PatientEducationProgress p = new PatientEducationProgress();
        p.setId(UUID.randomUUID());
        p.setPatientId(patientId);
        p.setResourceId(resourceId);
        p.setHospitalId(UUID.randomUUID());
        p.setProgressPercentage(percent);
        p.setAccessCount(1);
        p.setCompletedAt(completedAt);
        p.setComprehensionStatus(EducationComprehensionStatus.NOT_STARTED);
        return p;
    }

    @Nested
    @DisplayName("getMyEducation")
    class GetMyEducation {

        @Test
        @DisplayName("joins each progress row to its resource in one batch fetch")
        void joinsResources() {
            when(educationProgressRepository.findByPatientIdOrderByLastAccessedAtDesc(patientId))
                    .thenReturn(List.of(progress(50, null)));
            when(educationResourceRepository.findAllById(any())).thenReturn(List.of(resource(true)));

            List<PatientEducationItemDTO> result = service.getMyEducation(auth);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("Warning signs in pregnancy");
            assertThat(result.get(0).getProgressPercentage()).isEqualTo(50);
            assertThat(result.get(0).getResourceType()).isEqualTo(EducationResourceType.ARTICLE);
            // One batch call, never per-row lookups.
            verify(educationResourceRepository, never()).findById(any());
        }

        @Test
        @DisplayName("hides assignments whose resource was retired")
        void hidesInactiveResources() {
            when(educationProgressRepository.findByPatientIdOrderByLastAccessedAtDesc(patientId))
                    .thenReturn(List.of(progress(10, null)));
            when(educationResourceRepository.findAllById(any())).thenReturn(List.of(resource(false)));

            assertThat(service.getMyEducation(auth)).isEmpty();
        }

        @Test
        @DisplayName("hides assignments whose resource no longer exists")
        void hidesMissingResources() {
            when(educationProgressRepository.findByPatientIdOrderByLastAccessedAtDesc(patientId))
                    .thenReturn(List.of(progress(10, null)));
            when(educationResourceRepository.findAllById(any())).thenReturn(List.of());

            assertThat(service.getMyEducation(auth)).isEmpty();
        }

        @Test
        @DisplayName("returns empty without touching the resource table when nothing is assigned")
        void noAssignments() {
            when(educationProgressRepository.findByPatientIdOrderByLastAccessedAtDesc(patientId))
                    .thenReturn(List.of());

            assertThat(service.getMyEducation(auth)).isEmpty();
            verify(educationResourceRepository, never()).findAllById(any());
        }
    }

    @Nested
    @DisplayName("getMyEducationItem")
    class GetMyEducationItem {

        @Test
        @DisplayName("returns material assigned to this patient")
        void assigned_returnsItem() {
            when(educationProgressRepository
                    .findTopByPatientIdAndResourceIdOrderByCreatedAtDesc(patientId, resourceId))
                    .thenReturn(Optional.of(progress(0, null)));
            when(educationResourceRepository.findById(resourceId)).thenReturn(Optional.of(resource(true)));

            assertThat(service.getMyEducationItem(auth, resourceId).getResourceId()).isEqualTo(resourceId);
        }

        @Test
        @DisplayName("404s material that was never assigned to this patient")
        void notAssigned_throws() {
            when(educationProgressRepository
                    .findTopByPatientIdAndResourceIdOrderByCreatedAtDesc(patientId, resourceId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getMyEducationItem(auth, resourceId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("assigned to you");
        }
    }

    @Nested
    @DisplayName("updateMyEducationProgress")
    class UpdateMyEducationProgress {

        private void stubAssigned(PatientEducationProgress p, EducationResource r) {
            when(educationProgressRepository
                    .findTopByPatientIdAndResourceIdOrderByCreatedAtDesc(patientId, resourceId))
                    .thenReturn(Optional.of(p));
            when(educationResourceRepository.findById(resourceId)).thenReturn(Optional.of(r));
            when(educationProgressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("100% stamps completedAt, derives COMPLETED and bumps the resource completion count")
        void completion_stampsAndCounts() {
            PatientEducationProgress p = progress(40, null);
            EducationResource r = resource(true);
            stubAssigned(p, r);

            PatientEducationItemDTO result = service.updateMyEducationProgress(auth, resourceId,
                    PatientEducationProgressUpdateDTO.builder().progressPercentage(100).build());

            assertThat(p.getCompletedAt()).isNotNull();
            assertThat(result.getComprehensionStatus()).isEqualTo(EducationComprehensionStatus.COMPLETED);
            assertThat(r.getCompletionCount()).isEqualTo(1L);
            verify(educationResourceRepository).save(r);
        }

        @Test
        @DisplayName("does not double-count a resource completed earlier")
        void alreadyComplete_doesNotRecount() {
            PatientEducationProgress p = progress(100, LocalDateTime.now().minusDays(2));
            EducationResource r = resource(true);
            stubAssigned(p, r);

            service.updateMyEducationProgress(auth, resourceId,
                    PatientEducationProgressUpdateDTO.builder().progressPercentage(100).build());

            assertThat(r.getCompletionCount()).isZero();
            verify(educationResourceRepository, never()).save(any());
        }

        @Test
        @DisplayName("confirming understanding derives CONFIRMED_UNDERSTANDING")
        void confirmUnderstanding_derivesStatus() {
            PatientEducationProgress p = progress(100, LocalDateTime.now());
            stubAssigned(p, resource(true));

            PatientEducationItemDTO result = service.updateMyEducationProgress(auth, resourceId,
                    PatientEducationProgressUpdateDTO.builder().confirmedUnderstanding(true).build());

            assertThat(result.getComprehensionStatus())
                    .isEqualTo(EducationComprehensionStatus.CONFIRMED_UNDERSTANDING);
        }

        @Test
        @DisplayName("asking for clarification wins over completion in the derived status")
        void needsClarification_derivesStatus() {
            PatientEducationProgress p = progress(100, LocalDateTime.now());
            stubAssigned(p, resource(true));

            PatientEducationItemDTO result = service.updateMyEducationProgress(auth, resourceId,
                    PatientEducationProgressUpdateDTO.builder()
                            .needsClarification(true)
                            .clarificationRequest("What does pre-eclampsia mean?")
                            .build());

            assertThat(result.getComprehensionStatus())
                    .isEqualTo(EducationComprehensionStatus.NEEDS_CLARIFICATION);
        }

        @Test
        @DisplayName("a rating refreshes the resource average and rating count")
        void rating_updatesRollups() {
            PatientEducationProgress p = progress(100, LocalDateTime.now());
            EducationResource r = resource(true);
            stubAssigned(p, r);
            when(educationProgressRepository.calculateAverageRating(resourceId)).thenReturn(4.5);
            when(educationProgressRepository.countRatings(resourceId)).thenReturn(8L);

            service.updateMyEducationProgress(auth, resourceId,
                    PatientEducationProgressUpdateDTO.builder().rating(5).build());

            assertThat(r.getAverageRating()).isEqualTo(4.5);
            assertThat(r.getRatingCount()).isEqualTo(8L);
        }

        @Test
        @DisplayName("404s an update for material not assigned to this patient")
        void notAssigned_throws() {
            when(educationProgressRepository
                    .findTopByPatientIdAndResourceIdOrderByCreatedAtDesc(patientId, resourceId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateMyEducationProgress(auth, resourceId,
                    PatientEducationProgressUpdateDTO.builder().progressPercentage(50).build()))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(educationProgressRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("submitMyEducationQuestion")
    class SubmitQuestion {

        @Test
        @DisplayName("takes the hospital from the assignment when the question is about a resource")
        void resourceQuestion_usesAssignmentHospital() {
            PatientEducationProgress p = progress(0, null);
            when(educationProgressRepository
                    .findTopByPatientIdAndResourceIdOrderByCreatedAtDesc(patientId, resourceId))
                    .thenReturn(Optional.of(p));
            when(educationQuestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.submitMyEducationQuestion(auth, PatientEducationQuestionSubmitDTO.builder()
                    .resourceId(resourceId)
                    .questionText("Is this bleeding normal?")
                    .isUrgent(true)
                    .build());

            ArgumentCaptor<PatientEducationQuestion> captor =
                    ArgumentCaptor.forClass(PatientEducationQuestion.class);
            verify(educationQuestionRepository).save(captor.capture());
            PatientEducationQuestion saved = captor.getValue();
            assertThat(saved.getPatientId()).isEqualTo(patientId);
            assertThat(saved.getHospitalId()).isEqualTo(p.getHospitalId());
            assertThat(saved.getIsUrgent()).isTrue();
            // A patient can never submit a question that is already answered.
            assertThat(saved.getIsAnswered()).isFalse();
            assertThat(saved.getAnswer()).isNull();
        }

        @Test
        @DisplayName("404s a question about material not assigned to this patient")
        void unassignedResource_throws() {
            when(educationProgressRepository
                    .findTopByPatientIdAndResourceIdOrderByCreatedAtDesc(patientId, resourceId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.submitMyEducationQuestion(auth,
                    PatientEducationQuestionSubmitDTO.builder()
                            .resourceId(resourceId)
                            .questionText("Tell me about someone else's material")
                            .build()))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(educationQuestionRepository, never()).save(any());
        }
    }
}
