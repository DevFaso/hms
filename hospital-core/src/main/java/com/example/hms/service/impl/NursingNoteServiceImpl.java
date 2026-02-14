package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.NursingNoteMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.NursingNote;
import com.example.hms.model.NursingNoteAddendum;
import com.example.hms.model.NursingNoteEducationEntry;
import com.example.hms.model.NursingNoteInterventionEntry;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.nurse.NursingNoteAddendumRequestDTO;
import com.example.hms.payload.dto.nurse.NursingNoteCreateRequestDTO;
import com.example.hms.payload.dto.nurse.NursingNoteEducationDTO;
import com.example.hms.payload.dto.nurse.NursingNoteInterventionDTO;
import com.example.hms.payload.dto.nurse.NursingNoteResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.NursingNoteRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.NursingNoteService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class NursingNoteServiceImpl implements NursingNoteService {

    private static final int MAX_RESULTS = 50;

    private final NursingNoteRepository nursingNoteRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final UserRepository userRepository;
    private final MessageSource messageSource;
    private final RoleValidator roleValidator;
    private final AuditEventLogService auditEventLogService;
    private final NursingNoteMapper nursingNoteMapper;

    @Override
    @Transactional
    public NursingNoteResponseDTO createNote(NursingNoteCreateRequestDTO request, Locale locale) {
        Locale effectiveLocale = locale != null ? locale : Locale.getDefault();
        UUID actorUserId = requireCurrentUser();
        UUID hospitalId = resolveHospitalId(request.getHospitalId());
        ensureCanDocument(actorUserId, hospitalId, effectiveLocale);

        User author = userRepository.findById(actorUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Author user not found."));

        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage("patient.notFound", new Object[]{request.getPatientId()}, "Patient not found", effectiveLocale)
            ));

        ensurePatientRegistration(patient.getId(), hospitalId, effectiveLocale);

        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found."));

        Staff staff = staffRepository.findByUserIdAndHospitalId(actorUserId, hospitalId).orElse(null);

    NursingNote note = buildNoteSkeleton(request, author, patient, hospital, staff);

    List<NursingNoteEducationEntry> educationEntries = mapEducationEntries(request.getEducationEntries());
    note.setEducationEntries(new ArrayList<>(educationEntries));

    List<NursingNoteInterventionEntry> interventionEntries = mapInterventionEntries(request.getInterventionEntries());
    note.setInterventionEntries(new ArrayList<>(interventionEntries));

        if (note.getReadabilityScore() == null) {
            List<String> readabilitySegments = Stream.of(
                    note.getNarrative(),
                    note.getActionSummary(),
                    note.getResponseSummary(),
                    note.getEducationSummary()
                )
                .filter(Objects::nonNull)
                .toList();
            note.setReadabilityScore(computeReadabilityScore(readabilitySegments));
        }

        NursingNote saved = nursingNoteRepository.save(note);
        logAuditEvent(saved, patient, hospital, staff, actorUserId, "Nursing note documented");
        return nursingNoteMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public NursingNoteResponseDTO appendAddendum(UUID noteId, UUID hospitalId, NursingNoteAddendumRequestDTO request, Locale locale) {
        Locale effectiveLocale = locale != null ? locale : Locale.getDefault();
        UUID actorUserId = requireCurrentUser();
        UUID resolvedHospitalId = resolveHospitalId(hospitalId);
        ensureCanDocument(actorUserId, resolvedHospitalId, effectiveLocale);

        NursingNote note = nursingNoteRepository.findByIdAndHospital_Id(noteId, resolvedHospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Nursing note not found."));

        ensurePatientRegistration(note.getPatient().getId(), resolvedHospitalId, effectiveLocale);

        User author = userRepository.findById(actorUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Author user not found."));

        Staff staff = staffRepository.findByUserIdAndHospitalId(actorUserId, resolvedHospitalId).orElse(null);

        NursingNoteAddendum addendum = NursingNoteAddendum.builder()
            .note(note)
            .author(author)
            .authorStaff(staff)
            .authorName(resolveAuthorName(author, staff))
            .authorCredentials(resolveAuthorCredentials(staff))
            .content(request.getContent())
            .eventOccurredAt(toLocalDateTime(request.getEventOccurredAt()))
            .documentedAt(LocalDateTime.now())
            .attestAccuracy(Boolean.TRUE.equals(request.getAttestAccuracy()))
            .attestNoAbbreviations(Boolean.TRUE.equals(request.getAttestNoAbbreviations()))
            .build();

        if (Boolean.TRUE.equals(request.getAttestAccuracy()) && Boolean.TRUE.equals(request.getAttestNoAbbreviations())) {
            addendum.setSignedAt(LocalDateTime.now());
        }

        note.addAddendum(addendum);
        NursingNote saved = nursingNoteRepository.save(note);
        logAuditEvent(saved, note.getPatient(), note.getHospital(), staff, actorUserId, "Addendum added to nursing note");

        // Return the updated note representation
        return nursingNoteMapper.toResponse(saved);
    }

    @Override
    public List<NursingNoteResponseDTO> getRecentNotes(UUID patientId, UUID hospitalId, int limit, Locale locale) {
        if (patientId == null) {
            throw new BusinessException("Patient identifier is required to load nursing notes.");
        }
        UUID actorUserId = requireCurrentUser();
        UUID resolvedHospitalId = resolveHospitalId(hospitalId);
        ensureCanView(actorUserId, resolvedHospitalId, locale != null ? locale : Locale.getDefault());

        int effectiveLimit = limit <= 0 || limit > MAX_RESULTS ? MAX_RESULTS : limit;
        List<NursingNote> notes = nursingNoteRepository
            .findTop50ByPatient_IdAndHospital_IdOrderByCreatedAtDesc(patientId, resolvedHospitalId);

        return notes.stream()
            .limit(effectiveLimit)
            .map(nursingNoteMapper::toResponse)
            .toList();
    }

    @Override
    public NursingNoteResponseDTO getNote(UUID noteId, UUID hospitalId, Locale locale) {
        UUID actorUserId = requireCurrentUser();
        UUID resolvedHospitalId = resolveHospitalId(hospitalId);
        ensureCanView(actorUserId, resolvedHospitalId, locale != null ? locale : Locale.getDefault());

        NursingNote note = nursingNoteRepository.findByIdAndHospital_Id(noteId, resolvedHospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Nursing note not found."));
        return nursingNoteMapper.toResponse(note);
    }

    private NursingNote buildNoteSkeleton(
        NursingNoteCreateRequestDTO request,
        User author,
        Patient patient,
        Hospital hospital,
        Staff staff
    ) {
        NursingNote note = new NursingNote();
        note.setPatient(patient);
        note.setHospital(hospital);
        note.setAuthor(author);
        note.setAuthorStaff(staff);
        note.setAuthorName(resolveAuthorName(author, staff));
        note.setAuthorCredentials(resolveAuthorCredentials(staff));
        note.setTemplate(request.getTemplate());
        note.setDataSubjective(trimToNull(request.getDataSubjective()));
        note.setDataObjective(trimToNull(request.getDataObjective()));
        note.setDataAssessment(trimToNull(request.getDataAssessment()));
        note.setDataPlan(trimToNull(request.getDataPlan()));
        note.setDataImplementation(trimToNull(request.getDataImplementation()));
        note.setDataEvaluation(trimToNull(request.getDataEvaluation()));
        note.setActionSummary(trimToNull(request.getActionSummary()));
        note.setResponseSummary(trimToNull(request.getResponseSummary()));
        note.setEducationSummary(trimToNull(request.getEducationSummary()));
        note.setNarrative(trimToNull(request.getNarrative()));
        note.setLateEntry(request.isLateEntry());
        note.setEventOccurredAt(toLocalDateTime(request.getEventOccurredAt()));
        note.setDocumentedAt(LocalDateTime.now());
        note.setAttestAccuracy(Boolean.TRUE.equals(request.getAttestAccuracy()));
        note.setAttestSpellCheck(Boolean.TRUE.equals(request.getAttestSpellCheck()));
        note.setAttestNoAbbreviations(Boolean.TRUE.equals(request.getAttestNoAbbreviations()));
        note.setReadabilityScore(request.getReadabilityScore());

        if (request.isSignAndComplete()) {
            LocalDateTime now = LocalDateTime.now();
            note.setSignedAt(now);
            note.setSignedByName(Optional.ofNullable(trimToNull(request.getSignedByName()))
                .orElse(note.getAuthorName()));
            note.setSignedByCredentials(Optional.ofNullable(trimToNull(request.getSignedByCredentials()))
                .orElse(resolveAuthorCredentials(staff)));
        }

        return note;
    }

    private List<NursingNoteEducationEntry> mapEducationEntries(List<NursingNoteEducationDTO> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
            .filter(Objects::nonNull)
            .map(dto -> {
                boolean hasContent = StreamUtil.hasText(dto.getTopic(), dto.getTeachingMethod(), dto.getPatientUnderstanding(), dto.getReinforcementActions(), dto.getEducationSummary());
                if (!hasContent) {
                    return null;
                }
                return NursingNoteEducationEntry.builder()
                    .topic(trimToNull(dto.getTopic()))
                    .teachingMethod(trimToNull(dto.getTeachingMethod()))
                    .patientUnderstanding(trimToNull(dto.getPatientUnderstanding()))
                    .reinforcementActions(trimToNull(dto.getReinforcementActions()))
                    .educationSummary(trimToNull(dto.getEducationSummary()))
                    .build();
            })
            .filter(Objects::nonNull)
            .toList();
    }

    private List<NursingNoteInterventionEntry> mapInterventionEntries(List<NursingNoteInterventionDTO> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
            .filter(Objects::nonNull)
            .map(dto -> {
                boolean hasDescription = dto.getDescription() != null && !dto.getDescription().isBlank();
                boolean hasLinked = dto.getLinkedOrderId() != null || dto.getLinkedMedicationTaskId() != null;
                if (!hasDescription && !hasLinked && (dto.getFollowUpActions() == null || dto.getFollowUpActions().isBlank())) {
                    return null;
                }
                return NursingNoteInterventionEntry.builder()
                    .description(trimToNull(dto.getDescription()))
                    .linkedOrderId(dto.getLinkedOrderId())
                    .linkedMedicationTaskId(dto.getLinkedMedicationTaskId())
                    .followUpActions(trimToNull(dto.getFollowUpActions()))
                    .build();
            })
            .filter(Objects::nonNull)
            .toList();
    }

    private void ensurePatientRegistration(UUID patientId, UUID hospitalId, Locale locale) {
        boolean registered = registrationRepository
            .findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId)
            .isPresent();
        if (!registered) {
            throw new BusinessException(messageSource.getMessage(
                "patient.notRegisteredInHospital",
                new Object[]{patientId, hospitalId},
                "Patient is not registered in the specified hospital.",
                locale
            ));
        }
    }

    private void ensureCanDocument(UUID userId, UUID hospitalId, Locale locale) {
        boolean allowed = roleValidator.isNurse(userId, hospitalId)
            || roleValidator.isMidwife(userId, hospitalId)
            || roleValidator.isHospitalAdmin(userId, hospitalId)
            || roleValidator.isDoctor(userId, hospitalId)
            || roleValidator.isSuperAdminFromAuth();
        if (!allowed) {
            throw new BusinessException(messageSource.getMessage(
                "nursing.note.permission.denied",
                null,
                "You do not have permission to document nursing notes in this hospital.",
                locale
            ));
        }
    }

    private void ensureCanView(UUID userId, UUID hospitalId, Locale locale) {
        boolean allowed = roleValidator.isNurse(userId, hospitalId)
            || roleValidator.isMidwife(userId, hospitalId)
            || roleValidator.isDoctor(userId, hospitalId)
            || roleValidator.isHospitalAdmin(userId, hospitalId)
            || roleValidator.isSuperAdminFromAuth();
        if (!allowed) {
            throw new BusinessException(messageSource.getMessage(
                "nursing.note.view.denied",
                null,
                "You do not have permission to view nursing notes in this hospital.",
                locale
            ));
        }
    }

    private UUID requireCurrentUser() {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("Authenticated user context required.");
        }
        return userId;
    }

    private UUID resolveHospitalId(UUID requestedHospitalId) {
        if (requestedHospitalId != null) {
            return requestedHospitalId;
        }
        UUID resolved = roleValidator.getCurrentHospitalId();
        if (resolved == null) {
            throw new BusinessException("Hospital context is required for nursing documentation.");
        }
        return resolved;
    }

    private void logAuditEvent(NursingNote note, Patient patient, Hospital hospital, Staff staff, UUID actorUserId, String description) {
        Optional<UUID> assignmentId = resolveAssignmentId(staff);
        if (assignmentId.isEmpty()) {
            log.debug("Skipping audit log for nursing note {} because assignment context is unavailable.", note.getId());
            return;
        }
        String patientName = resolvePatientName(patient);
        AuditEventRequestDTO auditEvent = AuditEventRequestDTO.builder()
            .userId(actorUserId)
            .assignmentId(assignmentId.get())
            .eventType(AuditEventType.DATA_UPDATE)
            .status(AuditStatus.SUCCESS)
            .hospitalName(hospital != null ? hospital.getName() : null)
            .resourceName(patientName)
            .entityType("NURSING_NOTE")
            .resourceId(note.getId() != null ? note.getId().toString() : null)
            .eventDescription(description)
            .details(buildAuditDetails(note, patient, hospital))
            .build();
        auditEventLogService.logEvent(auditEvent);
    }

    private Map<String, Object> buildAuditDetails(NursingNote note, Patient patient, Hospital hospital) {
        Map<String, Object> details = new HashMap<>();
        if (patient != null) {
            details.put("patientId", patient.getId());
        }
        if (hospital != null) {
            details.put("hospitalId", hospital.getId());
        }
        if (note != null && note.getTemplate() != null) {
            details.put("template", note.getTemplate().name());
        }
        if (note != null) {
            details.put("lateEntry", note.isLateEntry());
        }
        return details;
    }

    private Optional<UUID> resolveAssignmentId(Staff staff) {
        if (staff != null && staff.getAssignment() != null) {
            return Optional.ofNullable(staff.getAssignment().getId());
        }
        UserRoleHospitalAssignment assignment = roleValidator.getCurrentAssignmentForHospital();
        if (assignment != null) {
            return Optional.ofNullable(assignment.getId());
        }
        return Optional.empty();
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime timestamp) {
        if (timestamp == null) {
            return null;
        }
        return timestamp.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Double computeReadabilityScore(List<String> textSegments) {
        if (textSegments == null || textSegments.isEmpty()) {
            return null;
        }
        String combined = textSegments.stream()
            .filter(str -> str != null && !str.isBlank())
            .collect(Collectors.joining(" "));
        if (combined.isBlank()) {
            return null;
        }

        String[] sentences = combined.split("[.!?]+\s*");
        int sentenceCount = Math.max(1, sentences.length);
        String[] words = combined.trim().split("\\s+");
        int wordCount = Math.max(1, words.length);
        int syllableCount = Math.max(1, countSyllables(words));

        double wordsPerSentence = (double) wordCount / sentenceCount;
        double syllablesPerWord = (double) syllableCount / wordCount;
        double flesch = 206.835 - (1.015 * wordsPerSentence) - (84.6 * syllablesPerWord);
        double normalized = Math.clamp(flesch, 0d, 100d);
        return Math.round(normalized * 100.0) / 100.0;
    }

    private int countSyllables(String[] words) {
        int syllables = 0;
        for (String word : words) {
            syllables += countSyllables(word);
        }
        return Math.max(syllables, words.length);
    }

    private int countSyllables(String word) {
        if (word == null || word.isBlank()) {
            return 1;
        }
        String normalized = word.toLowerCase(Locale.ROOT);
        int count = 0;
        boolean previousVowel = false;
        for (char c : normalized.toCharArray()) {
            boolean isVowel = "aeiouy".indexOf(c) >= 0;
            if (isVowel && !previousVowel) {
                count++;
            }
            previousVowel = isVowel;
        }
        if (normalized.endsWith("e") && count > 1) {
            count--;
        }
        return Math.max(count, 1);
    }

    private String resolveAuthorName(User author, Staff staff) {
        if (staff != null) {
            String staffName = staff.getFullName();
            if (staffName != null && !staffName.isBlank()) {
                return staffName;
            }
            if (staff.getName() != null && !staff.getName().isBlank()) {
                return staff.getName();
            }
        }
        if (author != null) {
            String joined = joinNames(author.getFirstName(), author.getLastName());
            if (joined != null && !joined.isBlank()) {
                return joined;
            }
            if (author.getEmail() != null) {
                return author.getEmail();
            }
        }
        return "Unknown";
    }

    private String resolveAuthorCredentials(Staff staff) {
        if (staff == null) {
            return null;
        }
        if (staff.getJobTitle() != null) {
            return formatJobTitle(staff.getJobTitle().name());
        }
        return staff.getLicenseNumber();
    }

    private String joinNames(String first, String last) {
        String f = first != null ? first.trim() : "";
        String l = last != null ? last.trim() : "";
        String combined = (f + " " + l).trim();
        return combined.isEmpty() ? null : combined;
    }

    private String formatJobTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.replace('_', ' ').toLowerCase(Locale.ROOT);
        String[] parts = normalized.split(" ");
        return java.util.Arrays.stream(parts)
            .filter(part -> !part.isBlank())
            .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1))
            .collect(Collectors.joining(" "));
    }

    private String resolvePatientName(Patient patient) {
        if (patient == null) {
            return null;
        }
        if (patient.getFullName() != null && !patient.getFullName().isBlank()) {
            return patient.getFullName();
        }
        User linkedUser = patient.getUser();
        if (linkedUser != null) {
            return joinNames(linkedUser.getFirstName(), linkedUser.getLastName());
        }
        return null;
    }

    /**
     * Lightweight util for checking if any text fields contain meaningful characters.
     */
    private static final class StreamUtil {
        private StreamUtil() {
        }

        private static boolean hasText(Object... fields) {
            if (fields == null) {
                return false;
            }
            for (Object field : fields) {
                if (field instanceof String s) {
                    if (!s.isBlank()) {
                        return true;
                    }
                } else if (field != null) {
                    return true;
                }
            }
            return false;
        }
    }
}
