package com.example.hms.service.impl;

import com.example.hms.enums.EducationCategory;
import com.example.hms.enums.EducationComprehensionStatus;
import com.example.hms.enums.EducationResourceType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.EducationResourceMapper;
import com.example.hms.mapper.PatientEducationProgressMapper;
import com.example.hms.mapper.PatientEducationQuestionMapper;
import com.example.hms.mapper.VisitEducationDocumentationMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.education.EducationResource;
import com.example.hms.model.education.PatientEducationProgress;
import com.example.hms.model.education.PatientEducationQuestion;
import com.example.hms.model.education.VisitEducationDocumentation;
import com.example.hms.payload.dto.education.*;
import com.example.hms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings({"java:S100", "java:S1450", "java:S1192"})
class PatientEducationServiceImplTest {

    @Mock
    private EducationResourceRepository resourceRepository;
    @Mock
    private PatientEducationProgressRepository progressRepository;
    @Mock
    private VisitEducationDocumentationRepository documentationRepository;
    @Mock
    private PatientEducationQuestionRepository questionRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private EncounterRepository encounterRepository;

    private EducationResourceMapper resourceMapper;
    private PatientEducationProgressMapper progressMapper;
    private VisitEducationDocumentationMapper documentationMapper;
    private PatientEducationQuestionMapper questionMapper;

    private PatientEducationServiceImpl service;

    private UUID hospitalId;
    private UUID patientId;
    private UUID staffId;
    private UUID resourceId;
    private Hospital hospital;
    private Patient patient;
    private Staff staff;
    private EducationResource resource;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        resourceMapper = new EducationResourceMapper();
        progressMapper = new PatientEducationProgressMapper();
        documentationMapper = new VisitEducationDocumentationMapper();
        questionMapper = new PatientEducationQuestionMapper();
        
        service = new PatientEducationServiceImpl(
            resourceRepository,
            progressRepository,
            documentationRepository,
            questionRepository,
            patientRepository,
            staffRepository,
            hospitalRepository,
            encounterRepository,
            resourceMapper,
            progressMapper,
            documentationMapper,
            questionMapper
        );

        hospitalId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        resourceId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Test Hospital");
        hospital.setCode("TEST-01");
        hospital.setActive(true);

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Jane");
        patient.setLastName("Doe");

        staff = new Staff();
        staff.setId(staffId);

        resource = EducationResource.builder()
            .hospitalId(hospitalId)
            .title("Prenatal Care 101")
            .description("Basic prenatal care information")
            .textContent("Content here")
            .resourceType(EducationResourceType.ARTICLE)
            .category(EducationCategory.PRENATAL_CARE)
            .primaryLanguage("en")
            .isActive(true)
            .isEvidenceBased(true)
            .createdBy("tester")
            .lastModifiedBy("tester")
            .build();
        resource.setId(resourceId);
    }

    // ==================== Resource Management Tests ====================

    @Test
    void createResource_shouldCreateNewResource() {
        // Given
        EducationResourceRequestDTO requestDTO = EducationResourceRequestDTO.builder()
            .title("New Resource")
            .description("Description")
            .textContent("Content")
            .resourceType(EducationResourceType.VIDEO)
            .category(EducationCategory.NUTRITION)
            .build();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(resourceRepository.save(any(EducationResource.class))).thenAnswer(invocation -> {
            EducationResource saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        // When
        EducationResourceResponseDTO result = service.createResource(requestDTO, hospitalId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("New Resource");
        assertThat(result.getResourceType()).isEqualTo(EducationResourceType.VIDEO);
        verify(resourceRepository).save(any(EducationResource.class));
    }

    @Test
    void createResource_shouldThrowExceptionWhenHospitalNotFound() {
        // Given
        EducationResourceRequestDTO requestDTO = EducationResourceRequestDTO.builder()
            .title("New Resource")
            .build();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> service.createResource(requestDTO, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Hospital not found");
    }

    @Test
    void updateResource_shouldUpdateExistingResource() {
        // Given
        EducationResourceRequestDTO requestDTO = EducationResourceRequestDTO.builder()
            .title("Updated Title")
            .description("Updated Description")
            .build();

        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        when(resourceRepository.save(any(EducationResource.class))).thenAnswer(i -> i.getArgument(0));

        // When
        EducationResourceResponseDTO result = service.updateResource(resourceId, requestDTO);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("Updated Title");
        verify(resourceRepository).save(resource);
    }

    @Test
    void getAllResources_shouldReturnActiveResourcesForHospital() {
        // Given
        when(resourceRepository.findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(hospitalId))
            .thenReturn(List.of(resource));

        // When
        List<EducationResourceResponseDTO> results = service.getAllResources(hospitalId);

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("Prenatal Care 101");
    }

    @Test
    void getResourceById_shouldReturnMappedDto() {
        // Given
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));

        // When
        EducationResourceResponseDTO result = service.getResourceById(resourceId);

        // Then
        assertThat(result.getId()).isEqualTo(resourceId);
        assertThat(result.getCategory()).isEqualTo(EducationCategory.PRENATAL_CARE);
    }

    @Test
    void getResourceFilters_shouldDelegateToRepositoryQueries() {
        // Given
        when(resourceRepository.findByCategoryAndHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(
            EducationCategory.PRENATAL_CARE, hospitalId)).thenReturn(List.of(resource));
        when(resourceRepository.findByResourceTypeAndHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(
            EducationResourceType.ARTICLE, hospitalId)).thenReturn(List.of(resource));
        when(resourceRepository.findByPrimaryLanguageAndHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(
            "en", hospitalId)).thenReturn(List.of(resource));
        when(resourceRepository.findPopularResourcesByCategory(EducationCategory.PRENATAL_CARE, hospitalId))
            .thenReturn(List.of(resource));

        // When
        List<EducationResourceResponseDTO> byCategory = service.getResourcesByCategory(EducationCategory.PRENATAL_CARE, hospitalId);
        List<EducationResourceResponseDTO> byType = service.getResourcesByType(EducationResourceType.ARTICLE, hospitalId);
        List<EducationResourceResponseDTO> byLanguage = service.getResourcesByLanguage("en", hospitalId);
        List<EducationResourceResponseDTO> popular = service.getPopularResourcesByCategory(EducationCategory.PRENATAL_CARE, hospitalId);

        // Then
        assertThat(byCategory).hasSize(1);
        assertThat(byType).hasSize(1);
        assertThat(byLanguage).hasSize(1);
        assertThat(popular).hasSize(1);
    }

    @Test
    void searchResources_shouldFindMatchingResources() {
        // Given
        when(resourceRepository.searchResources("prenatal", hospitalId))
            .thenReturn(List.of(resource));

        // When
        List<EducationResourceResponseDTO> results = service.searchResources("prenatal", hospitalId);

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).contains("Prenatal");
    }

    @Test
    void incrementResourceViewCount_shouldIncreaseViewCount() {
        // Given
        Long initialViewCount = resource.getViewCount();
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        when(resourceRepository.save(any(EducationResource.class))).thenAnswer(i -> i.getArgument(0));

        // When
        service.incrementResourceViewCount(resourceId);

        // Then
        ArgumentCaptor<EducationResource> captor = ArgumentCaptor.forClass(EducationResource.class);
        verify(resourceRepository).save(captor.capture());
        assertThat(captor.getValue().getViewCount()).isEqualTo(initialViewCount + 1);
    }

    @Test
    void deleteResource_shouldSoftDeleteResource() {
        // Given
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        when(resourceRepository.save(any(EducationResource.class))).thenAnswer(i -> i.getArgument(0));

        // When
        service.deleteResource(resourceId);

        // Then
        ArgumentCaptor<EducationResource> captor = ArgumentCaptor.forClass(EducationResource.class);
        verify(resourceRepository).save(captor.capture());
        EducationResource saved = captor.getValue();
        assertThat(saved.getIsActive()).isFalse();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void deleteResource_shouldThrowWhenResourceMissing() {
        // Given
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> service.deleteResource(resourceId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Education resource not found");
    }

    // ==================== Progress Tracking Tests ====================

    @Test
    void trackProgress_shouldCreateNewProgressWhenNotExists() {
        // Given
        PatientEducationProgressRequestDTO requestDTO = PatientEducationProgressRequestDTO.builder()
            .resourceId(resourceId)
            .comprehensionStatus(EducationComprehensionStatus.IN_PROGRESS)
            .progressPercentage(50)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(progressRepository.findTopByPatientIdAndResourceIdOrderByCreatedAtDesc(patientId, resourceId))
            .thenReturn(Optional.empty());
        when(progressRepository.save(any(PatientEducationProgress.class))).thenAnswer(invocation -> {
            PatientEducationProgress saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        // When
        PatientEducationProgressResponseDTO result = service.trackProgress(patientId, requestDTO, hospitalId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getProgressPercentage()).isEqualTo(50);
        verify(progressRepository).save(any(PatientEducationProgress.class));
    }

    @Test
    void trackProgress_shouldUpdateExistingProgress() {
        // Given
        PatientEducationProgress existingProgress = PatientEducationProgress.builder()
            .patientId(patientId)
            .resourceId(resourceId)
            .hospitalId(hospitalId)
            .comprehensionStatus(EducationComprehensionStatus.IN_PROGRESS)
            .accessCount(5)
            .progressPercentage(30)
            .build();
        existingProgress.setId(UUID.randomUUID());

        PatientEducationProgressRequestDTO requestDTO = PatientEducationProgressRequestDTO.builder()
            .resourceId(resourceId)
            .progressPercentage(60)
            .rating(4)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(progressRepository.findTopByPatientIdAndResourceIdOrderByCreatedAtDesc(patientId, resourceId))
            .thenReturn(Optional.of(existingProgress));
        when(progressRepository.save(any(PatientEducationProgress.class))).thenAnswer(i -> i.getArgument(0));
        when(progressRepository.calculateAverageRating(resourceId)).thenReturn(4.0);
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));

        // When
        PatientEducationProgressResponseDTO result = service.trackProgress(patientId, requestDTO, hospitalId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getProgressPercentage()).isEqualTo(60);
        assertThat(result.getAccessCount()).isEqualTo(6); // Incremented
    }

    @Test
    void trackProgress_shouldMarkCompletedWhenProgressIs100() {
        // Given
        PatientEducationProgressRequestDTO requestDTO = PatientEducationProgressRequestDTO.builder()
            .resourceId(resourceId)
            .progressPercentage(100)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(progressRepository.findTopByPatientIdAndResourceIdOrderByCreatedAtDesc(patientId, resourceId))
            .thenReturn(Optional.empty());
        when(progressRepository.save(any(PatientEducationProgress.class))).thenAnswer(invocation -> {
            PatientEducationProgress saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        // When
        PatientEducationProgressResponseDTO result = service.trackProgress(patientId, requestDTO, hospitalId);

        // Then
        ArgumentCaptor<PatientEducationProgress> captor = ArgumentCaptor.forClass(PatientEducationProgress.class);
        verify(progressRepository).save(captor.capture());
        assertThat(captor.getValue().getCompletedAt()).isNotNull();
        assertThat(captor.getValue().getComprehensionStatus()).isEqualTo(EducationComprehensionStatus.COMPLETED);
        assertThat(result.getComprehensionStatus()).isEqualTo(EducationComprehensionStatus.COMPLETED);
    }

    @Test
    void updateProgress_shouldCompleteAndRefreshAverageRating() {
        // Given
        UUID progressId = UUID.randomUUID();
        PatientEducationProgress progress = PatientEducationProgress.builder()
            .patientId(patientId)
            .resourceId(resourceId)
            .hospitalId(hospitalId)
            .progressPercentage(80)
            .comprehensionStatus(EducationComprehensionStatus.IN_PROGRESS)
            .accessCount(2)
            .build();
        progress.setId(progressId);

        PatientEducationProgressRequestDTO requestDTO = PatientEducationProgressRequestDTO.builder()
            .progressPercentage(100)
            .rating(5)
            .build();

        when(progressRepository.findById(progressId)).thenReturn(Optional.of(progress));
        when(progressRepository.save(any(PatientEducationProgress.class))).thenAnswer(i -> i.getArgument(0));
        when(progressRepository.calculateAverageRating(resourceId)).thenReturn(4.5);
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        when(resourceRepository.save(any(EducationResource.class))).thenAnswer(i -> i.getArgument(0));

        // When
        PatientEducationProgressResponseDTO result = service.updateProgress(progressId, requestDTO);

        // Then
    assertThat(result.getComprehensionStatus()).isEqualTo(EducationComprehensionStatus.COMPLETED);
    assertThat(result.getCompletedAt()).isNotNull();
        verify(resourceRepository).save(resource);
        assertThat(resource.getAverageRating()).isEqualTo(4.5);
    }

    @Test
    void updateProgress_shouldThrowWhenProgressMissing() {
        // Given
        UUID progressId = UUID.randomUUID();
        PatientEducationProgressRequestDTO requestDTO = PatientEducationProgressRequestDTO.builder()
            .progressPercentage(10)
            .build();

        when(progressRepository.findById(progressId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> service.updateProgress(progressId, requestDTO))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Progress record not found");
    }

    @Test
    void getPatientProgress_shouldReturnAllProgressForPatient() {
        // Given
        PatientEducationProgress progress = PatientEducationProgress.builder()
            .patientId(patientId)
            .resourceId(resourceId)
            .hospitalId(hospitalId)
            .progressPercentage(75)
            .build();
        progress.setId(UUID.randomUUID());

        when(progressRepository.findByPatientIdOrderByLastAccessedAtDesc(patientId)).thenReturn(List.of(progress));

        // When
        List<PatientEducationProgressResponseDTO> results = service.getPatientProgress(patientId);

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getProgressPercentage()).isEqualTo(75);
    }

    @Test
    void progressLookupsShouldReturnMappedDtosAndAggregates() {
        // Given
        PatientEducationProgress inProgress = PatientEducationProgress.builder()
            .patientId(patientId)
            .resourceId(resourceId)
            .progressPercentage(40)
            .comprehensionStatus(EducationComprehensionStatus.IN_PROGRESS)
            .build();
        inProgress.setId(UUID.randomUUID());

        PatientEducationProgress completed = PatientEducationProgress.builder()
            .patientId(patientId)
            .resourceId(resourceId)
            .progressPercentage(100)
            .comprehensionStatus(EducationComprehensionStatus.COMPLETED)
            .build();
        completed.setId(UUID.randomUUID());

        when(progressRepository.findInProgressResources(patientId)).thenReturn(List.of(inProgress));
        when(progressRepository.findCompletedResources(patientId)).thenReturn(List.of(completed));
        when(progressRepository.countCompletedResources(patientId)).thenReturn(3L);
        when(progressRepository.calculateAverageRating(resourceId)).thenReturn(4.2);

        // When
        List<PatientEducationProgressResponseDTO> inProgressResults = service.getInProgressResources(patientId);
        List<PatientEducationProgressResponseDTO> completedResults = service.getCompletedResources(patientId);
        Long completedCount = service.countCompletedResources(patientId);
        Double averageRating = service.calculateAverageRating(resourceId);

        // Then
        assertThat(inProgressResults).hasSize(1);
        assertThat(inProgressResults.get(0).getProgressPercentage()).isEqualTo(40);
        assertThat(completedResults).hasSize(1);
        assertThat(completedResults.get(0).getComprehensionStatus()).isEqualTo(EducationComprehensionStatus.COMPLETED);
        assertThat(completedCount).isEqualTo(3L);
        assertThat(averageRating).isEqualTo(4.2);
    }

    // ==================== Question Management Tests ====================

    @Test
    void submitQuestion_shouldCreateNewQuestion() {
        // Given
        PatientEducationQuestionRequestDTO requestDTO = PatientEducationQuestionRequestDTO.builder()
            .resourceId(resourceId)
            .questionText("What should I eat during pregnancy?")
            .isUrgent(false)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        when(questionRepository.save(any(PatientEducationQuestion.class))).thenAnswer(invocation -> {
            PatientEducationQuestion saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        // When
        PatientEducationQuestionResponseDTO result = service.submitQuestion(patientId, requestDTO, hospitalId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getQuestionText()).isEqualTo("What should I eat during pregnancy?");
        assertThat(result.getIsAnswered()).isFalse();
        verify(questionRepository).save(any(PatientEducationQuestion.class));
    }

    @Test
    void answerQuestion_shouldUpdateQuestionWithAnswer() {
        // Given
        UUID questionId = UUID.randomUUID();
        PatientEducationQuestion question = PatientEducationQuestion.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .question("What should I eat?")
            .isAnswered(false)
            .build();
        question.setId(questionId);

        String answerText = "Eat a balanced diet with plenty of fruits and vegetables.";

        when(questionRepository.findById(questionId)).thenReturn(Optional.of(question));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(questionRepository.save(any(PatientEducationQuestion.class))).thenAnswer(i -> i.getArgument(0));

        // When
        PatientEducationQuestionResponseDTO result = service.answerQuestion(questionId, answerText, staffId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getIsAnswered()).isTrue();
        ArgumentCaptor<PatientEducationQuestion> captor = ArgumentCaptor.forClass(PatientEducationQuestion.class);
        verify(questionRepository).save(captor.capture());
        assertThat(captor.getValue().getAnswer()).isEqualTo(answerText);
        assertThat(captor.getValue().getAnsweredAt()).isNotNull();
    }

    @Test
    void getUnansweredQuestions_shouldReturnOnlyUnansweredQuestions() {
        // Given
        PatientEducationQuestion unansweredQuestion = PatientEducationQuestion.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .question("Unanswered question")
            .isAnswered(false)
            .build();
        unansweredQuestion.setId(UUID.randomUUID());

        when(questionRepository.findByIsAnsweredFalseAndHospitalIdOrderByIsUrgentDescCreatedAtDesc(hospitalId))
            .thenReturn(List.of(unansweredQuestion));

        // When
        List<PatientEducationQuestionResponseDTO> results = service.getUnansweredQuestions(hospitalId);

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getIsAnswered()).isFalse();
    }

    @Test
    void getUrgentQuestions_shouldReturnOnlyUrgentQuestions() {
        // Given
        PatientEducationQuestion urgentQuestion = PatientEducationQuestion.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .question("Urgent question")
            .isUrgent(true)
            .isAnswered(false)
            .build();
        urgentQuestion.setId(UUID.randomUUID());

        when(questionRepository.findByHospitalIdAndIsUrgentTrueAndIsAnsweredFalseOrderByCreatedAtAsc(hospitalId))
            .thenReturn(List.of(urgentQuestion));

        // When
        List<PatientEducationQuestionResponseDTO> results = service.getUrgentQuestions(hospitalId);

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getIsUrgent()).isTrue();
    }

    @Test
    void getQuestionsRequiringAppointment_shouldReturnFollowUpItems() {
        // Given
        PatientEducationQuestion needsAppointment = PatientEducationQuestion.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .question("Follow-up needed")
            .isAnswered(false)
            .requiresInPersonDiscussion(true)
            .appointmentScheduled(false)
            .build();
        needsAppointment.setId(UUID.randomUUID());

        when(questionRepository.findUnansweredRequiringAppointment(hospitalId))
            .thenReturn(List.of(needsAppointment));

        // When
        List<PatientEducationQuestionResponseDTO> results = service.getQuestionsRequiringAppointment(hospitalId);

        // Then
        assertThat(results).hasSize(1);
        PatientEducationQuestionResponseDTO dto = results.get(0);
        assertThat(dto.getRequiresInPersonDiscussion()).isTrue();
        assertThat(dto.getAppointmentScheduled()).isFalse();
    }

    @Test
    void updateQuestion_shouldApplyRequestValues() {
        // Given
        UUID questionId = UUID.randomUUID();
        PatientEducationQuestion existing = PatientEducationQuestion.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .question("Original question")
            .isUrgent(false)
            .build();
        existing.setId(questionId);

        PatientEducationQuestionRequestDTO requestDTO = PatientEducationQuestionRequestDTO.builder()
            .questionText("Updated question text")
            .isUrgent(true)
            .requiresInPersonDiscussion(true)
            .build();

        when(questionRepository.findById(questionId)).thenReturn(Optional.of(existing));
        when(questionRepository.save(existing)).thenReturn(existing);

        // When
        PatientEducationQuestionResponseDTO result = service.updateQuestion(questionId, requestDTO);

        // Then
        verify(questionRepository).save(existing);
        assertThat(result.getQuestionText()).isEqualTo("Updated question text");
        assertThat(existing.getIsUrgent()).isTrue();
        assertThat(existing.getRequiresInPersonDiscussion()).isTrue();
    }

    @Test
    void questionLookups_shouldReturnMappedDtos() {
        // Given
        UUID questionId = UUID.randomUUID();
        PatientEducationQuestion question = PatientEducationQuestion.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .question("Follow-up question")
            .build();
        question.setId(questionId);

        when(questionRepository.findById(questionId)).thenReturn(Optional.of(question));
        when(questionRepository.findByPatientIdOrderByCreatedAtDesc(patientId)).thenReturn(List.of(question));

        // When
        PatientEducationQuestionResponseDTO byId = service.getQuestionById(questionId);
        List<PatientEducationQuestionResponseDTO> byPatient = service.getPatientQuestions(patientId);

        // Then
        assertThat(byId.getQuestionText()).isEqualTo("Follow-up question");
        assertThat(byPatient).hasSize(1);
        assertThat(byPatient.get(0).getQuestionText()).isEqualTo("Follow-up question");
    }

    // ==================== Visit Documentation Tests ====================

    @Test
    void documentVisitEducation_shouldCreateDocumentation() {
        // Given
        UUID encounterId = UUID.randomUUID();
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);

        VisitEducationDocumentationRequestDTO requestDTO = VisitEducationDocumentationRequestDTO.builder()
            .encounterId(encounterId)
            .patientId(patientId)
            .category(EducationCategory.NUTRITION)
            .topicDiscussed("Nutrition counseling")
            .discussionNotes("Gestational diabetes prevention")
            .nutritionDiscussed(true)
            .warningSignsDiscussed(true)
            .build();

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(documentationRepository.save(any(VisitEducationDocumentation.class))).thenAnswer(invocation -> {
            VisitEducationDocumentation saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        // When
        VisitEducationDocumentationResponseDTO result = service.documentVisitEducation(staffId, requestDTO, hospitalId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getNutritionDiscussed()).isTrue();
        assertThat(result.getWarningSignsDiscussed()).isTrue();
        verify(documentationRepository).save(any(VisitEducationDocumentation.class));
    }

    @Test
    void updateVisitDocumentation_shouldPersistUpdates() {
        // Given
        UUID documentationId = UUID.randomUUID();
        VisitEducationDocumentation existing = VisitEducationDocumentation.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .topicDiscussed("Original topic")
            .discussionNotes("Original notes")
            .build();
        existing.setId(documentationId);

        VisitEducationDocumentationRequestDTO requestDTO = VisitEducationDocumentationRequestDTO.builder()
            .topicDiscussed("Updated topic")
            .discussionNotes("Refreshed notes")
            .build();

        when(documentationRepository.findById(documentationId)).thenReturn(Optional.of(existing));
        when(documentationRepository.save(existing)).thenReturn(existing);

        // When
        VisitEducationDocumentationResponseDTO result = service.updateVisitDocumentation(documentationId, requestDTO);

        // Then
        verify(documentationRepository).save(existing);
        assertThat(result.getTopicDiscussed()).isEqualTo("Updated topic");
        assertThat(existing.getDiscussionNotes()).isEqualTo("Refreshed notes");
    }

    @Test
    void visitDocumentationLookups_shouldReturnMappedDtos() {
        // Given
        UUID documentationId = UUID.randomUUID();
        VisitEducationDocumentation documentation = VisitEducationDocumentation.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .topicDiscussed("Discharge planning")
            .build();
        documentation.setId(documentationId);

        when(documentationRepository.findById(documentationId)).thenReturn(Optional.of(documentation));
        when(documentationRepository.findByPatientIdOrderByCreatedAtDesc(patientId)).thenReturn(List.of(documentation));

        // When
        VisitEducationDocumentationResponseDTO byId = service.getVisitDocumentationById(documentationId);
        List<VisitEducationDocumentationResponseDTO> byPatient = service.getPatientVisitDocumentation(patientId);

        // Then
        assertThat(byId.getTopicDiscussed()).isEqualTo("Discharge planning");
        assertThat(byPatient).hasSize(1);
        assertThat(byPatient.get(0).getTopicDiscussed()).isEqualTo("Discharge planning");
    }

    @Test
    void hasDiscussedCategory_shouldReturnTrueWhenCategoryDiscussed() {
        // Given
        when(documentationRepository.hasDiscussedCategory(patientId, EducationCategory.NUTRITION))
            .thenReturn(true);

        // When
        boolean result = service.hasDiscussedCategory(patientId, EducationCategory.NUTRITION);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void hasDiscussedCategory_shouldReturnFalseWhenCategoryNotDiscussed() {
        // Given
        when(documentationRepository.hasDiscussedCategory(patientId, EducationCategory.EXERCISE))
            .thenReturn(false);

        // When
        boolean result = service.hasDiscussedCategory(patientId, EducationCategory.EXERCISE);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void getRecentProviderDocumentation_shouldUseLookbackWindow() {
        // Given
        VisitEducationDocumentation doc = VisitEducationDocumentation.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .staffId(staffId)
            .topicDiscussed("Nutrition")
            .build();
        doc.setId(UUID.randomUUID());

        when(documentationRepository.findRecentByProvider(eq(staffId), any(LocalDateTime.class)))
            .thenReturn(List.of(doc));

        // When
        List<VisitEducationDocumentationResponseDTO> results = service.getRecentProviderDocumentation(staffId, 7);

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTopicDiscussed()).isEqualTo("Nutrition");
        verify(documentationRepository).findRecentByProvider(eq(staffId), any(LocalDateTime.class));
    }
}
