package com.example.hms.service;

import com.example.hms.enums.EducationCategory;
import com.example.hms.enums.EducationResourceType;
import com.example.hms.payload.dto.education.EducationResourceRequestDTO;
import com.example.hms.payload.dto.education.EducationResourceResponseDTO;
import com.example.hms.payload.dto.education.PatientEducationProgressRequestDTO;
import com.example.hms.payload.dto.education.PatientEducationProgressResponseDTO;
import com.example.hms.payload.dto.education.PatientEducationQuestionRequestDTO;
import com.example.hms.payload.dto.education.PatientEducationQuestionResponseDTO;
import com.example.hms.payload.dto.education.VisitEducationDocumentationRequestDTO;
import com.example.hms.payload.dto.education.VisitEducationDocumentationResponseDTO;

import java.util.List;
import java.util.UUID;

public interface PatientEducationService {

    // Education Resource Management
    EducationResourceResponseDTO createResource(EducationResourceRequestDTO requestDTO, UUID hospitalId);
    EducationResourceResponseDTO updateResource(UUID id, EducationResourceRequestDTO requestDTO);
    EducationResourceResponseDTO getResourceById(UUID id);
    List<EducationResourceResponseDTO> getAllResources(UUID hospitalId);
    List<EducationResourceResponseDTO> searchResources(String searchTerm, UUID hospitalId);
    List<EducationResourceResponseDTO> getResourcesByCategory(EducationCategory category, UUID hospitalId);
    List<EducationResourceResponseDTO> getResourcesByType(EducationResourceType type, UUID hospitalId);
    List<EducationResourceResponseDTO> getResourcesByLanguage(String languageCode, UUID hospitalId);
    List<EducationResourceResponseDTO> getPopularResourcesByCategory(EducationCategory category, UUID hospitalId);
    void deleteResource(UUID id);
    void incrementResourceViewCount(UUID resourceId);

    // Patient Progress Management
    PatientEducationProgressResponseDTO trackProgress(UUID patientId, PatientEducationProgressRequestDTO requestDTO, UUID hospitalId);
    PatientEducationProgressResponseDTO updateProgress(UUID progressId, PatientEducationProgressRequestDTO requestDTO);
    PatientEducationProgressResponseDTO getProgressById(UUID progressId);
    List<PatientEducationProgressResponseDTO> getPatientProgress(UUID patientId);
    List<PatientEducationProgressResponseDTO> getInProgressResources(UUID patientId);
    List<PatientEducationProgressResponseDTO> getCompletedResources(UUID patientId);
    Long countCompletedResources(UUID patientId);
    Double calculateAverageRating(UUID resourceId);

    // Visit Education Documentation
    VisitEducationDocumentationResponseDTO documentVisitEducation(
        UUID staffId, VisitEducationDocumentationRequestDTO requestDTO, UUID hospitalId);
    VisitEducationDocumentationResponseDTO updateVisitDocumentation(
        UUID documentationId, VisitEducationDocumentationRequestDTO requestDTO);
    VisitEducationDocumentationResponseDTO getVisitDocumentationById(UUID documentationId);
    List<VisitEducationDocumentationResponseDTO> getPatientVisitDocumentation(UUID patientId);
    List<VisitEducationDocumentationResponseDTO> getRecentProviderDocumentation(UUID staffId, int daysBack);
    boolean hasDiscussedCategory(UUID patientId, EducationCategory category);

    // Patient Questions
    PatientEducationQuestionResponseDTO submitQuestion(
        UUID patientId, PatientEducationQuestionRequestDTO requestDTO, UUID hospitalId);
    PatientEducationQuestionResponseDTO answerQuestion(
        UUID questionId, String answerText, UUID staffId);
    PatientEducationQuestionResponseDTO updateQuestion(
        UUID questionId, PatientEducationQuestionRequestDTO requestDTO);
    PatientEducationQuestionResponseDTO getQuestionById(UUID questionId);
    List<PatientEducationQuestionResponseDTO> getPatientQuestions(UUID patientId);
    List<PatientEducationQuestionResponseDTO> getUnansweredQuestions(UUID hospitalId);
    List<PatientEducationQuestionResponseDTO> getUrgentQuestions(UUID hospitalId);
    List<PatientEducationQuestionResponseDTO> getQuestionsRequiringAppointment(UUID hospitalId);
}
