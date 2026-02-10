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
import com.example.hms.payload.dto.education.EducationResourceRequestDTO;
import com.example.hms.payload.dto.education.EducationResourceResponseDTO;
import com.example.hms.payload.dto.education.PatientEducationProgressRequestDTO;
import com.example.hms.payload.dto.education.PatientEducationProgressResponseDTO;
import com.example.hms.payload.dto.education.PatientEducationQuestionRequestDTO;
import com.example.hms.payload.dto.education.PatientEducationQuestionResponseDTO;
import com.example.hms.payload.dto.education.VisitEducationDocumentationRequestDTO;
import com.example.hms.payload.dto.education.VisitEducationDocumentationResponseDTO;
import com.example.hms.repository.EducationResourceRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientEducationProgressRepository;
import com.example.hms.repository.PatientEducationQuestionRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.VisitEducationDocumentationRepository;
import com.example.hms.service.PatientEducationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PatientEducationServiceImpl implements PatientEducationService {

    private static final String RESOURCE_NOT_FOUND = "Education resource not found with id: ";
    private static final String PATIENT_NOT_FOUND = "Patient not found with id: ";
    private static final String HOSPITAL_NOT_FOUND = "Hospital not found with id: ";
    private static final String STAFF_NOT_FOUND = "Staff not found with id: ";
    private static final String ENCOUNTER_NOT_FOUND = "Encounter not found with id: ";
    private static final String PROGRESS_NOT_FOUND = "Progress record not found with id: ";
    private static final String QUESTION_NOT_FOUND = "Question not found with id: ";
    private static final String DOCUMENTATION_NOT_FOUND = "Visit documentation not found with id: ";

    private final EducationResourceRepository resourceRepository;
    private final PatientEducationProgressRepository progressRepository;
    private final VisitEducationDocumentationRepository documentationRepository;
    private final PatientEducationQuestionRepository questionRepository;
    private final PatientRepository patientRepository;
    private final StaffRepository staffRepository;
    private final HospitalRepository hospitalRepository;
    private final EncounterRepository encounterRepository;

    private final EducationResourceMapper resourceMapper;
    private final PatientEducationProgressMapper progressMapper;
    private final VisitEducationDocumentationMapper documentationMapper;
    private final PatientEducationQuestionMapper questionMapper;

    // ==================== Education Resource Management ====================

    @Override
    public EducationResourceResponseDTO createResource(EducationResourceRequestDTO requestDTO, UUID hospitalId) {
        log.info("Creating education resource for hospital: {}", hospitalId);

        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND + hospitalId));

        EducationResource resource = resourceMapper.toEntity(requestDTO);
        resource.setHospitalId(hospital.getId());
        if (resource.getOrganizationId() == null && hospital.getOrganization() != null) {
            resource.setOrganizationId(hospital.getOrganization().getId());
        }
        if (resource.getCreatedBy() == null) {
            resource.setCreatedBy("system");
        }
        resource.setLastModifiedBy(resource.getLastModifiedBy() != null ? resource.getLastModifiedBy() : "system");
        resource.setUpdatedAt(LocalDateTime.now());

        EducationResource savedResource = resourceRepository.save(resource);
        log.info("Created education resource with id: {}", savedResource.getId());

        return resourceMapper.toResponseDTO(savedResource);
    }

    @Override
    public EducationResourceResponseDTO updateResource(UUID id, EducationResourceRequestDTO requestDTO) {
        log.info("Updating education resource: {}", id);

        EducationResource resource = resourceRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NOT_FOUND + id));

        resourceMapper.updateEntityFromDTO(requestDTO, resource);
        resource.setUpdatedAt(LocalDateTime.now());

        EducationResource updatedResource = resourceRepository.save(resource);
        log.info("Updated education resource: {}", id);

        return resourceMapper.toResponseDTO(updatedResource);
    }

    @Override
    @Transactional(readOnly = true)
    public EducationResourceResponseDTO getResourceById(UUID id) {
        EducationResource resource = resourceRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NOT_FOUND + id));
        return resourceMapper.toResponseDTO(resource);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EducationResourceResponseDTO> getAllResources(UUID hospitalId) {
        return resourceRepository.findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(hospitalId).stream()
            .map(resourceMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EducationResourceResponseDTO> searchResources(String searchTerm, UUID hospitalId) {
        return resourceRepository.searchResources(searchTerm, hospitalId).stream()
            .map(resourceMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EducationResourceResponseDTO> getResourcesByCategory(EducationCategory category, UUID hospitalId) {
        return resourceRepository.findByCategoryAndHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(category, hospitalId).stream()
            .map(resourceMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EducationResourceResponseDTO> getResourcesByType(EducationResourceType type, UUID hospitalId) {
        return resourceRepository.findByResourceTypeAndHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(type, hospitalId).stream()
            .map(resourceMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EducationResourceResponseDTO> getResourcesByLanguage(String languageCode, UUID hospitalId) {
        return resourceRepository.findByPrimaryLanguageAndHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(languageCode, hospitalId).stream()
            .map(resourceMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EducationResourceResponseDTO> getPopularResourcesByCategory(EducationCategory category, UUID hospitalId) {
        return resourceRepository.findPopularResourcesByCategory(category, hospitalId).stream()
            .map(resourceMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    public void deleteResource(UUID id) {
        log.info("Deleting education resource: {}", id);

        EducationResource resource = resourceRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NOT_FOUND + id));

        resource.setIsActive(false);
        resource.setUpdatedAt(LocalDateTime.now());
        resourceRepository.save(resource);

        log.info("Soft deleted education resource: {}", id);
    }

    @Override
    public void incrementResourceViewCount(UUID resourceId) {
        EducationResource resource = resourceRepository.findById(resourceId)
            .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NOT_FOUND + resourceId));

        resource.setViewCount(resource.getViewCount() + 1);
        resource.setUpdatedAt(LocalDateTime.now());
        resourceRepository.save(resource);
    }

    // ==================== Patient Progress Management ====================

    @Override
    public PatientEducationProgressResponseDTO trackProgress(
            UUID patientId, PatientEducationProgressRequestDTO requestDTO, UUID hospitalId) {
        log.info("Tracking progress for patient: {} on resource: {}", patientId, requestDTO.getResourceId());

        patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(PATIENT_NOT_FOUND + patientId));

        EducationResource resource = resourceRepository.findById(requestDTO.getResourceId())
            .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NOT_FOUND + requestDTO.getResourceId()));

        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND + hospitalId));

        PatientEducationProgress progress = progressRepository
            .findTopByPatientIdAndResourceIdOrderByCreatedAtDesc(patientId, requestDTO.getResourceId())
            .orElseGet(() -> {
                PatientEducationProgress newProgress = new PatientEducationProgress();
                newProgress.setPatientId(patientId);
                newProgress.setResourceId(resource.getId());
                newProgress.setHospitalId(hospital.getId());
                newProgress.setStartedAt(LocalDateTime.now());
                newProgress.setAccessCount(0);
                newProgress.setTimeSpentSeconds(0L);
                newProgress.setComprehensionStatus(
                    requestDTO.getComprehensionStatus() != null
                        ? requestDTO.getComprehensionStatus()
                        : EducationComprehensionStatus.NOT_STARTED
                );
                return newProgress;
            });

        progress.setPatientId(patientId);
        progress.setResourceId(resource.getId());
        progress.setHospitalId(hospital.getId());
        progressMapper.updateEntityFromDTO(requestDTO, progress);
        progress.setLastAccessedAt(LocalDateTime.now());
        progress.setAccessCount(progress.getAccessCount() + 1);
        progress.setUpdatedAt(LocalDateTime.now());

        // Set completion time if 100% complete
        if (requestDTO.getProgressPercentage() != null && requestDTO.getProgressPercentage() >= 100
                && progress.getCompletedAt() == null) {
            progress.setCompletedAt(LocalDateTime.now());
            progress.setComprehensionStatus(EducationComprehensionStatus.COMPLETED);
        }

        PatientEducationProgress savedProgress = progressRepository.save(progress);

        // Update resource average rating if rating provided
        if (requestDTO.getRating() != null) {
            updateResourceAverageRating(resource.getId());
        }

        log.info("Tracked progress with id: {}", savedProgress.getId());
        return progressMapper.toResponseDTO(savedProgress);
    }

    @Override
    public PatientEducationProgressResponseDTO updateProgress(UUID progressId, PatientEducationProgressRequestDTO requestDTO) {
        log.info("Updating progress: {}", progressId);

        PatientEducationProgress progress = progressRepository.findById(progressId)
            .orElseThrow(() -> new ResourceNotFoundException(PROGRESS_NOT_FOUND + progressId));

        progressMapper.updateEntityFromDTO(requestDTO, progress);
        progress.setLastAccessedAt(LocalDateTime.now());
        progress.setUpdatedAt(LocalDateTime.now());

        // Set completion time if 100% complete
        if (requestDTO.getProgressPercentage() != null && requestDTO.getProgressPercentage() >= 100
                && progress.getCompletedAt() == null) {
            progress.setCompletedAt(LocalDateTime.now());
            progress.setComprehensionStatus(EducationComprehensionStatus.COMPLETED);
        }

        PatientEducationProgress updatedProgress = progressRepository.save(progress);

        // Update resource average rating if rating changed
        if (requestDTO.getRating() != null) {
            updateResourceAverageRating(progress.getResourceId());
        }

        return progressMapper.toResponseDTO(updatedProgress);
    }

    @Override
    @Transactional(readOnly = true)
    public PatientEducationProgressResponseDTO getProgressById(UUID progressId) {
        PatientEducationProgress progress = progressRepository.findById(progressId)
            .orElseThrow(() -> new ResourceNotFoundException(PROGRESS_NOT_FOUND + progressId));
        return progressMapper.toResponseDTO(progress);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientEducationProgressResponseDTO> getPatientProgress(UUID patientId) {
        return progressRepository.findByPatientIdOrderByLastAccessedAtDesc(patientId).stream()
            .map(progressMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientEducationProgressResponseDTO> getInProgressResources(UUID patientId) {
        return progressRepository.findInProgressResources(patientId).stream()
            .map(progressMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientEducationProgressResponseDTO> getCompletedResources(UUID patientId) {
        return progressRepository.findCompletedResources(patientId).stream()
            .map(progressMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Long countCompletedResources(UUID patientId) {
        return progressRepository.countCompletedResources(patientId);
    }

    @Override
    @Transactional(readOnly = true)
    public Double calculateAverageRating(UUID resourceId) {
        return progressRepository.calculateAverageRating(resourceId);
    }

    private void updateResourceAverageRating(UUID resourceId) {
        Double avgRating = progressRepository.calculateAverageRating(resourceId);
        if (avgRating != null) {
            EducationResource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NOT_FOUND + resourceId));
            resource.setAverageRating(avgRating);
            resource.setUpdatedAt(LocalDateTime.now());
            resourceRepository.save(resource);
        }
    }

    // ==================== Visit Education Documentation ====================

    @Override
    public VisitEducationDocumentationResponseDTO documentVisitEducation(
            UUID staffId, VisitEducationDocumentationRequestDTO requestDTO, UUID hospitalId) {
        log.info("Documenting visit education for encounter: {}", requestDTO.getEncounterId());

        staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException(STAFF_NOT_FOUND + staffId));

        patientRepository.findById(requestDTO.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException(PATIENT_NOT_FOUND + requestDTO.getPatientId()));

        encounterRepository.findById(requestDTO.getEncounterId())
            .orElseThrow(() -> new ResourceNotFoundException(ENCOUNTER_NOT_FOUND + requestDTO.getEncounterId()));

        hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND + hospitalId));

        VisitEducationDocumentation documentation = documentationMapper.toEntity(requestDTO);
        documentation.setStaffId(staffId);
        documentation.setPatientId(requestDTO.getPatientId());
        documentation.setEncounterId(requestDTO.getEncounterId());
        documentation.setHospitalId(hospitalId);

        VisitEducationDocumentation savedDocumentation = documentationRepository.save(documentation);
        log.info("Documented visit education with id: {}", savedDocumentation.getId());

        return documentationMapper.toResponseDTO(savedDocumentation);
    }

    @Override
    public VisitEducationDocumentationResponseDTO updateVisitDocumentation(
            UUID documentationId, VisitEducationDocumentationRequestDTO requestDTO) {
        log.info("Updating visit education documentation: {}", documentationId);

        VisitEducationDocumentation documentation = documentationRepository.findById(documentationId)
            .orElseThrow(() -> new ResourceNotFoundException(DOCUMENTATION_NOT_FOUND + documentationId));

        documentationMapper.updateEntityFromDTO(requestDTO, documentation);

        VisitEducationDocumentation updatedDocumentation = documentationRepository.save(documentation);
        return documentationMapper.toResponseDTO(updatedDocumentation);
    }

    @Override
    @Transactional(readOnly = true)
    public VisitEducationDocumentationResponseDTO getVisitDocumentationById(UUID documentationId) {
        VisitEducationDocumentation documentation = documentationRepository.findById(documentationId)
            .orElseThrow(() -> new ResourceNotFoundException(DOCUMENTATION_NOT_FOUND + documentationId));
        return documentationMapper.toResponseDTO(documentation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisitEducationDocumentationResponseDTO> getPatientVisitDocumentation(UUID patientId) {
        return documentationRepository.findByPatientIdOrderByCreatedAtDesc(patientId).stream()
            .map(documentationMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisitEducationDocumentationResponseDTO> getRecentProviderDocumentation(UUID staffId, int daysBack) {
        LocalDateTime since = LocalDateTime.now().minusDays(daysBack);
        return documentationRepository.findRecentByProvider(staffId, since).stream()
            .map(documentationMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasDiscussedCategory(UUID patientId, EducationCategory category) {
        return documentationRepository.hasDiscussedCategory(patientId, category);
    }

    // ==================== Patient Questions ====================

    @Override
    public PatientEducationQuestionResponseDTO submitQuestion(
            UUID patientId, PatientEducationQuestionRequestDTO requestDTO, UUID hospitalId) {
        log.info("Submitting question from patient: {}", patientId);

        patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(PATIENT_NOT_FOUND + patientId));

        hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND + hospitalId));

        PatientEducationQuestion question = questionMapper.toEntity(requestDTO);
        question.setPatientId(patientId);
        question.setHospitalId(hospitalId);

        if (requestDTO.getResourceId() != null) {
            EducationResource resource = resourceRepository.findById(requestDTO.getResourceId())
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NOT_FOUND + requestDTO.getResourceId()));
            question.setResourceId(resource.getId());
        }

        question.setIsAnswered(Boolean.FALSE);
        question.setAppointmentScheduled(Boolean.FALSE);

        PatientEducationQuestion savedQuestion = questionRepository.save(question);
        log.info("Submitted question with id: {}", savedQuestion.getId());

        return questionMapper.toResponseDTO(savedQuestion);
    }

    @Override
    public PatientEducationQuestionResponseDTO answerQuestion(UUID questionId, String answerText, UUID staffId) {
        log.info("Answering question: {} by staff: {}", questionId, staffId);

        PatientEducationQuestion question = questionRepository.findById(questionId)
            .orElseThrow(() -> new ResourceNotFoundException(QUESTION_NOT_FOUND + questionId));

        staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException(STAFF_NOT_FOUND + staffId));

        question.setAnswer(answerText);
        question.setAnsweredByStaffId(staffId);
        question.setAnsweredAt(LocalDateTime.now());
        question.setIsAnswered(true);
        question.setUpdatedAt(LocalDateTime.now());

        PatientEducationQuestion updatedQuestion = questionRepository.save(question);
        log.info("Answered question: {}", questionId);

        return questionMapper.toResponseDTO(updatedQuestion);
    }

    @Override
    public PatientEducationQuestionResponseDTO updateQuestion(UUID questionId, PatientEducationQuestionRequestDTO requestDTO) {
        log.info("Updating question: {}", questionId);

        PatientEducationQuestion question = questionRepository.findById(questionId)
            .orElseThrow(() -> new ResourceNotFoundException(QUESTION_NOT_FOUND + questionId));

        questionMapper.updateEntityFromDTO(requestDTO, question);
        question.setUpdatedAt(LocalDateTime.now());

        PatientEducationQuestion updatedQuestion = questionRepository.save(question);
        return questionMapper.toResponseDTO(updatedQuestion);
    }

    @Override
    @Transactional(readOnly = true)
    public PatientEducationQuestionResponseDTO getQuestionById(UUID questionId) {
        PatientEducationQuestion question = questionRepository.findById(questionId)
            .orElseThrow(() -> new ResourceNotFoundException(QUESTION_NOT_FOUND + questionId));
        return questionMapper.toResponseDTO(question);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientEducationQuestionResponseDTO> getPatientQuestions(UUID patientId) {
        return questionRepository.findByPatientIdOrderByCreatedAtDesc(patientId).stream()
            .map(questionMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientEducationQuestionResponseDTO> getUnansweredQuestions(UUID hospitalId) {
        return questionRepository.findByIsAnsweredFalseAndHospitalIdOrderByIsUrgentDescCreatedAtDesc(hospitalId).stream()
            .map(questionMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientEducationQuestionResponseDTO> getUrgentQuestions(UUID hospitalId) {
        return questionRepository.findByHospitalIdAndIsUrgentTrueAndIsAnsweredFalseOrderByCreatedAtAsc(hospitalId).stream()
            .map(questionMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientEducationQuestionResponseDTO> getQuestionsRequiringAppointment(UUID hospitalId) {
        return questionRepository.findUnansweredRequiringAppointment(hospitalId).stream()
            .map(questionMapper::toResponseDTO)
            .collect(Collectors.toList());
    }
}
