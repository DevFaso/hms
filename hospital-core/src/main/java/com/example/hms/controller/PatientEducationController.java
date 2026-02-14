package com.example.hms.controller;

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
import com.example.hms.service.PatientEducationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/patient-education")
@RequiredArgsConstructor
@Tag(name = "Patient Education", description = "Patient education resource management and progress tracking")
@SecurityRequirement(name = "bearer-jwt")
public class PatientEducationController {

    private final PatientEducationService educationService;

    // ==================== Education Resource Endpoints ====================

    @PostMapping("/resources")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Create a new education resource",
               description = "Create educational content for patients")
    public ResponseEntity<EducationResourceResponseDTO> createResource(
            @Valid @RequestBody EducationResourceRequestDTO requestDTO,
            Authentication auth) {
        UUID hospitalId = getRequiredHospitalId(auth);
        EducationResourceResponseDTO response = educationService.createResource(requestDTO, hospitalId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/resources/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Update an education resource")
    public ResponseEntity<EducationResourceResponseDTO> updateResource(
            @PathVariable UUID id,
            @Valid @RequestBody EducationResourceRequestDTO requestDTO) {
        EducationResourceResponseDTO response = educationService.updateResource(id, requestDTO);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/resources/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get education resource by ID")
    public ResponseEntity<EducationResourceResponseDTO> getResourceById(@PathVariable UUID id) {
        educationService.incrementResourceViewCount(id);
        EducationResourceResponseDTO response = educationService.getResourceById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/resources")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get all education resources")
    public ResponseEntity<List<EducationResourceResponseDTO>> getAllResources(Authentication auth) {
        UUID hospitalId = getRequiredHospitalId(auth);
        List<EducationResourceResponseDTO> response = educationService.getAllResources(hospitalId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/resources/search")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Search education resources by keyword")
    public ResponseEntity<List<EducationResourceResponseDTO>> searchResources(
            @RequestParam String query,
            Authentication auth) {
        UUID hospitalId = getRequiredHospitalId(auth);
        List<EducationResourceResponseDTO> response = educationService.searchResources(query, hospitalId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/resources/by-category/{category}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get education resources by category")
    public ResponseEntity<List<EducationResourceResponseDTO>> getResourcesByCategory(
            @PathVariable EducationCategory category,
            Authentication auth) {
        UUID hospitalId = getRequiredHospitalId(auth);
        List<EducationResourceResponseDTO> response = educationService.getResourcesByCategory(category, hospitalId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/resources/by-type/{type}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get education resources by type")
    public ResponseEntity<List<EducationResourceResponseDTO>> getResourcesByType(
            @PathVariable EducationResourceType type,
            Authentication auth) {
        UUID hospitalId = getRequiredHospitalId(auth);
        List<EducationResourceResponseDTO> response = educationService.getResourcesByType(type, hospitalId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/resources/by-language/{languageCode}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get education resources by language")
    public ResponseEntity<List<EducationResourceResponseDTO>> getResourcesByLanguage(
            @PathVariable String languageCode,
            Authentication auth) {
        UUID hospitalId = getRequiredHospitalId(auth);
        List<EducationResourceResponseDTO> response = educationService.getResourcesByLanguage(languageCode, hospitalId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/resources/popular/{category}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get popular resources by category")
    public ResponseEntity<List<EducationResourceResponseDTO>> getPopularResourcesByCategory(
            @PathVariable EducationCategory category,
            Authentication auth) {
        UUID hospitalId = getRequiredHospitalId(auth);
        List<EducationResourceResponseDTO> response = educationService.getPopularResourcesByCategory(category, hospitalId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/resources/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN')")
    @Operation(summary = "Delete an education resource")
    public ResponseEntity<Void> deleteResource(@PathVariable UUID id) {
        educationService.deleteResource(id);
        return ResponseEntity.noContent().build();
    }

    // ==================== Patient Progress Endpoints ====================

    @PostMapping("/progress")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_RECEPTIONIST')")
    @Operation(summary = "Track patient education progress",
               description = "Record patient interaction with an education resource")
    public ResponseEntity<PatientEducationProgressResponseDTO> trackProgress(
            @RequestParam UUID patientId,
            @Valid @RequestBody PatientEducationProgressRequestDTO requestDTO,
            Authentication auth) {
        UUID hospitalId = getRequiredHospitalId(auth);
        PatientEducationProgressResponseDTO response = educationService.trackProgress(patientId, requestDTO, hospitalId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/progress/{progressId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Update patient education progress")
    public ResponseEntity<PatientEducationProgressResponseDTO> updateProgress(
            @PathVariable UUID progressId,
            @Valid @RequestBody PatientEducationProgressRequestDTO requestDTO) {
        PatientEducationProgressResponseDTO response = educationService.updateProgress(progressId, requestDTO);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/progress/{progressId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get progress record by ID")
    public ResponseEntity<PatientEducationProgressResponseDTO> getProgressById(@PathVariable UUID progressId) {
        PatientEducationProgressResponseDTO response = educationService.getProgressById(progressId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/progress/patient/{patientId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Get all progress for a patient")
    public ResponseEntity<List<PatientEducationProgressResponseDTO>> getPatientProgress(@PathVariable UUID patientId) {
        List<PatientEducationProgressResponseDTO> response = educationService.getPatientProgress(patientId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/progress/patient/{patientId}/in-progress")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Get in-progress resources for a patient")
    public ResponseEntity<List<PatientEducationProgressResponseDTO>> getInProgressResources(@PathVariable UUID patientId) {
        List<PatientEducationProgressResponseDTO> response = educationService.getInProgressResources(patientId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/progress/patient/{patientId}/completed")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Get completed resources for a patient")
    public ResponseEntity<List<PatientEducationProgressResponseDTO>> getCompletedResources(@PathVariable UUID patientId) {
        List<PatientEducationProgressResponseDTO> response = educationService.getCompletedResources(patientId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/progress/patient/{patientId}/count")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Count completed resources for a patient")
    public ResponseEntity<Long> countCompletedResources(@PathVariable UUID patientId) {
        Long count = educationService.countCompletedResources(patientId);
        return ResponseEntity.ok(count);
    }

    @GetMapping("/resources/{resourceId}/average-rating")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get average rating for a resource")
    public ResponseEntity<Double> getAverageRating(@PathVariable UUID resourceId) {
        Double rating = educationService.calculateAverageRating(resourceId);
        return ResponseEntity.ok(rating);
    }

    // ==================== Visit Documentation Endpoints ====================

    @PostMapping("/visit-documentation")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Document education provided during visit",
               description = "Record topics discussed and resources provided during prenatal visit")
    public ResponseEntity<VisitEducationDocumentationResponseDTO> documentVisitEducation(
            @Valid @RequestBody VisitEducationDocumentationRequestDTO requestDTO,
            Authentication auth) {
        UUID providerId = getRequiredUserId(auth);
        UUID hospitalId = getRequiredHospitalId(auth);
        VisitEducationDocumentationResponseDTO response = educationService.documentVisitEducation(
            providerId, requestDTO, hospitalId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/visit-documentation/{documentationId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Update visit education documentation")
    public ResponseEntity<VisitEducationDocumentationResponseDTO> updateVisitDocumentation(
            @PathVariable UUID documentationId,
            @Valid @RequestBody VisitEducationDocumentationRequestDTO requestDTO) {
        VisitEducationDocumentationResponseDTO response = educationService.updateVisitDocumentation(documentationId, requestDTO);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/visit-documentation/{documentationId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Get visit documentation by ID")
    public ResponseEntity<VisitEducationDocumentationResponseDTO> getVisitDocumentationById(@PathVariable UUID documentationId) {
        VisitEducationDocumentationResponseDTO response = educationService.getVisitDocumentationById(documentationId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/visit-documentation/patient/{patientId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Get all visit documentation for a patient")
    public ResponseEntity<List<VisitEducationDocumentationResponseDTO>> getPatientVisitDocumentation(@PathVariable UUID patientId) {
        List<VisitEducationDocumentationResponseDTO> response = educationService.getPatientVisitDocumentation(patientId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/visit-documentation/provider/recent")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Get recent documentation by current provider")
    public ResponseEntity<List<VisitEducationDocumentationResponseDTO>> getRecentProviderDocumentation(
            @RequestParam(defaultValue = "30") int daysBack,
            Authentication auth) {
        UUID providerId = getRequiredUserId(auth);
        List<VisitEducationDocumentationResponseDTO> response = educationService.getRecentProviderDocumentation(
            providerId, daysBack);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/visit-documentation/patient/{patientId}/has-discussed/{category}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Check if category has been discussed with patient")
    public ResponseEntity<Boolean> hasDiscussedCategory(
            @PathVariable UUID patientId,
            @PathVariable EducationCategory category) {
        boolean hasDiscussed = educationService.hasDiscussedCategory(patientId, category);
        return ResponseEntity.ok(hasDiscussed);
    }

    // ==================== Patient Question Endpoints ====================

    @PostMapping("/questions")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_RECEPTIONIST')")
    @Operation(summary = "Submit a patient question",
               description = "Patient submits a question about educational content")
    public ResponseEntity<PatientEducationQuestionResponseDTO> submitQuestion(
            @RequestParam UUID patientId,
            @Valid @RequestBody PatientEducationQuestionRequestDTO requestDTO,
            Authentication auth) {
        UUID hospitalId = getRequiredHospitalId(auth);
        PatientEducationQuestionResponseDTO response = educationService.submitQuestion(patientId, requestDTO, hospitalId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/questions/{questionId}/answer")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Answer a patient question")
    public ResponseEntity<PatientEducationQuestionResponseDTO> answerQuestion(
            @PathVariable UUID questionId,
            @RequestParam String answerText,
            Authentication auth) {
        UUID responderId = getRequiredUserId(auth);
        PatientEducationQuestionResponseDTO response = educationService.answerQuestion(questionId, answerText, responderId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/questions/{questionId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Update a patient question")
    public ResponseEntity<PatientEducationQuestionResponseDTO> updateQuestion(
            @PathVariable UUID questionId,
            @Valid @RequestBody PatientEducationQuestionRequestDTO requestDTO) {
        PatientEducationQuestionResponseDTO response = educationService.updateQuestion(questionId, requestDTO);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/questions/{questionId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get question by ID")
    public ResponseEntity<PatientEducationQuestionResponseDTO> getQuestionById(@PathVariable UUID questionId) {
        PatientEducationQuestionResponseDTO response = educationService.getQuestionById(questionId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/questions/patient/{patientId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Get all questions from a patient")
    public ResponseEntity<List<PatientEducationQuestionResponseDTO>> getPatientQuestions(@PathVariable UUID patientId) {
        List<PatientEducationQuestionResponseDTO> response = educationService.getPatientQuestions(patientId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/questions/unanswered")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Get all unanswered questions")
    public ResponseEntity<List<PatientEducationQuestionResponseDTO>> getUnansweredQuestions(Authentication auth) {
        UUID hospitalId = getRequiredHospitalId(auth);
        List<PatientEducationQuestionResponseDTO> response = educationService.getUnansweredQuestions(hospitalId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/questions/urgent")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Get urgent questions")
    public ResponseEntity<List<PatientEducationQuestionResponseDTO>> getUrgentQuestions(Authentication auth) {
        UUID hospitalId = getRequiredHospitalId(auth);
        List<PatientEducationQuestionResponseDTO> response = educationService.getUrgentQuestions(hospitalId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/questions/requiring-appointment")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Get questions requiring in-person appointment")
    public ResponseEntity<List<PatientEducationQuestionResponseDTO>> getQuestionsRequiringAppointment(Authentication auth) {
        UUID hospitalId = getRequiredHospitalId(auth);
        List<PatientEducationQuestionResponseDTO> response = educationService.getQuestionsRequiringAppointment(hospitalId);
        return ResponseEntity.ok(response);
    }

    // ==================== Helper Methods ====================

    private UUID getRequiredHospitalId(Authentication auth) {
        UUID hospitalId = extractHospitalIdFromJwt(auth);
        if (hospitalId == null) {
            throw new AccessDeniedException("Missing hospitalId in authentication token");
        }
        return hospitalId;
    }

    private UUID getRequiredUserId(Authentication auth) {
        UUID userId = extractUserIdFromJwt(auth);
        if (userId == null) {
            throw new AccessDeniedException("Missing userId in authentication token");
        }
        return userId;
    }

    private UUID extractHospitalIdFromJwt(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jat) {
            Jwt jwt = jat.getToken();
            String s = jwt.getClaimAsString("hospitalId");
            if (s != null && !s.isBlank()) {
                try {
                    return UUID.fromString(s);
                } catch (RuntimeException ignored) {
                    // ignore invalid UUID format
                }
            }
            Object raw = jwt.getClaims().get("hospitalId");
            if (raw instanceof UUID u) return u;
            if (raw instanceof String str && !str.isBlank()) {
                try {
                    return UUID.fromString(str);
                } catch (RuntimeException ignored) {
                    // ignore invalid UUID format
                }
            }
        }
        return null;
    }

    private UUID extractUserIdFromJwt(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jat) {
            Jwt jwt = jat.getToken();
            String sub = jwt.getSubject();
            if (sub != null && !sub.isBlank()) {
                try {
                    return UUID.fromString(sub);
                } catch (RuntimeException ignored) {
                    // ignore invalid UUID format
                }
            }
        }
        return null;
    }
}
