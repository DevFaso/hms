package com.example.hms.service;

import com.example.hms.enums.EncounterNoteLinkType;
import com.example.hms.enums.EncounterNoteTemplate;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.EncounterMapper;
import com.example.hms.model.*;
import com.example.hms.model.referral.ObgynReferral;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.model.encounter.EncounterNoteAddendum;
import com.example.hms.model.encounter.EncounterNoteHistory;
import com.example.hms.model.encounter.EncounterNoteLink;
import com.example.hms.payload.dto.*;
import com.example.hms.repository.*;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Path;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.example.hms.mapper.PatientHospitalRegistrationMapper.joinName;


@Service
@RequiredArgsConstructor
public class EncounterServiceImpl implements EncounterService {

    private static final String NOTE_HISTORY_CREATED = "NOTE_CREATED";
    private static final String NOTE_HISTORY_UPDATED = "NOTE_UPDATED";
    private static final String NOTE_HISTORY_ADDENDUM = "NOTE_ADDENDUM";

    private static final String MSG_ENCOUNTER_NOT_FOUND = "encounter.notfound";
    private static final String MSG_PATIENT_NOT_FOUND = "patient.notfound";
    private static final String MSG_STAFF_NOT_FOUND = "staff.notfound";
    private static final String MSG_HOSPITAL_NOT_FOUND = "hospital.notfound";
    private static final String MSG_ASSIGNMENT_NOT_FOUND = "assignment.notfound";
    private static final String MSG_ENCOUNTER_STAFF_INVALID = "encounter.staff.invalid";
    private static final String MSG_ENCOUNTER_STAFF_HOSPITAL_MISMATCH = "encounter.staff.hospital.mismatch";

    private static final List<TextFieldMapping> NOTE_TEXT_FIELDS = List.of(
        new TextFieldMapping(EncounterNoteRequestDTO::getChiefComplaint, EncounterNote::setChiefComplaint),
        new TextFieldMapping(EncounterNoteRequestDTO::getHistoryOfPresentIllness, EncounterNote::setHistoryOfPresentIllness),
        new TextFieldMapping(EncounterNoteRequestDTO::getReviewOfSystems, EncounterNote::setReviewOfSystems),
        new TextFieldMapping(EncounterNoteRequestDTO::getPhysicalExam, EncounterNote::setPhysicalExam),
        new TextFieldMapping(EncounterNoteRequestDTO::getDiagnosticResults, EncounterNote::setDiagnosticResults),
        new TextFieldMapping(EncounterNoteRequestDTO::getSubjective, EncounterNote::setSubjective),
        new TextFieldMapping(EncounterNoteRequestDTO::getObjective, EncounterNote::setObjective),
        new TextFieldMapping(EncounterNoteRequestDTO::getAssessment, EncounterNote::setAssessment),
        new TextFieldMapping(EncounterNoteRequestDTO::getPlan, EncounterNote::setPlan),
        new TextFieldMapping(EncounterNoteRequestDTO::getImplementation, EncounterNote::setImplementation),
        new TextFieldMapping(EncounterNoteRequestDTO::getEvaluation, EncounterNote::setEvaluation),
        new TextFieldMapping(EncounterNoteRequestDTO::getPatientInstructions, EncounterNote::setPatientInstructions),
        new TextFieldMapping(EncounterNoteRequestDTO::getSummary, EncounterNote::setSummary)
    );

    // Helper to validate staff role
    private void validateStaffRole(UUID userId, UUID hospitalId, Locale locale) {
        boolean allowed =
            roleValidator.isDoctor(userId, hospitalId) ||
            roleValidator.isNurse(userId, hospitalId)  ||
            roleValidator.isHospitalAdmin(userId, hospitalId);
        if (!allowed) {
            String msg = messageSource.getMessage(MSG_ENCOUNTER_STAFF_INVALID, null, locale);
            throw new BusinessException(msg);
        }
    }

    // EncounterServiceImpl.java

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<EncounterResponseDTO> getEncountersByDoctorIdentifier(String identifier, Locale locale) {
        UUID staffId = resolveStaffIdByIdentifier(identifier, locale);
        Staff staff = staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(MSG_STAFF_NOT_FOUND, null, locale)));

        // Optional: validate role in hospital context
        UUID hospitalId = staff.getHospital() != null ? staff.getHospital().getId() : null;
        if (staff.getUser() == null || !roleValidator.isDoctor(staff.getUser().getId(), hospitalId)) {
            throw new BusinessException(messageSource.getMessage(MSG_ENCOUNTER_STAFF_INVALID, null, locale));
        }

        return encounterRepository.findByStaff_Id(staffId).stream()
            .map(encounterMapper::toEncounterResponseDTO)
            .collect(java.util.stream.Collectors.toList());
    }

    /** Accepts UUID | email | username | license/roleCode and returns Staff ID. */
    private UUID resolveStaffIdByIdentifier(String identifier, Locale locale) {
        // UUID?
        try {
            return UUID.fromString(identifier);
        } catch (IllegalArgumentException ignored) {
            // Identifier is not a UUID; fall through to other resolution strategies.
        }

        // Email?
        if (identifier.contains("@")) {
            java.util.List<Staff> matches = staffRepository.findByUserEmail(identifier);
            if (!matches.isEmpty()) return matches.get(0).getId();
        }

        // Username / license / role code
        return staffRepository.findByUsernameOrLicenseOrRoleCode(identifier)
            .map(Staff::getId)
            .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(MSG_STAFF_NOT_FOUND, null, locale)));
    }


    // Helper to validate appointment-patient/hospital match
    private void validateAppointment(Appointment appointment, Patient patient, UUID hospitalId, Locale locale) {
        if (appointment != null) {
            if (appointment.getPatient() == null || !appointment.getPatient().getId().equals(patient.getId())) {
                String msg = messageSource.getMessage("encounter.appointment.patient.mismatch", null, locale);
                throw new BusinessException(msg);
            }
            if (appointment.getHospital() == null || !appointment.getHospital().getId().equals(hospitalId)) {
                String msg = messageSource.getMessage("encounter.appointment.hospital.mismatch", null, locale);
                throw new BusinessException(msg);
            }
        }
    }
    // Helper methods to resolve identifiers
    private UUID resolvePatientId(EncounterRequestDTO dto, Locale locale) {
        if (dto.getPatientId() != null) return dto.getPatientId();
        if (dto.getPatientIdentifier() != null) {
            Patient patient = patientRepository.findByUsernameOrEmail(dto.getPatientIdentifier())
                .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(MSG_PATIENT_NOT_FOUND, null, locale)));
            return patient.getId();
        }
        throw new IllegalArgumentException(messageSource.getMessage("patient.identifier.required", null, locale));
    }

    private UUID resolveStaffId(EncounterRequestDTO dto, Locale locale) {
        if (dto.getStaffId() != null) return dto.getStaffId();
        // Try staffIdentifier first
        if (dto.getStaffIdentifier() != null) {
            Staff staff = staffRepository.findByUsernameOrLicenseOrRoleCode(dto.getStaffIdentifier())
                .orElse(null);
            if (staff != null) return staff.getId();
        }
        // Try staffEmail if present
        if (dto.getStaffEmail() != null) {
            List<Staff> staffList = staffRepository.findByUserEmail(dto.getStaffEmail());
            if (!staffList.isEmpty()) return staffList.get(0).getId();
        }
        throw new IllegalArgumentException(messageSource.getMessage("staff.identifier.required", null, locale));
    }

    private UUID resolveHospitalId(EncounterRequestDTO dto, Locale locale) {
        if (dto.getHospitalId() != null) return dto.getHospitalId();
        if (dto.getHospitalIdentifier() != null) {
            Hospital hospital = hospitalRepository.findByNameOrCodeOrEmail(dto.getHospitalIdentifier())
                .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(MSG_HOSPITAL_NOT_FOUND, null, locale)));
            return hospital.getId();
        }
        throw new IllegalArgumentException(messageSource.getMessage("hospital.identifier.required", null, locale));
    }

    private UUID resolveDepartmentId(EncounterRequestDTO dto, Hospital hospital, Locale locale) {
        if (dto.getDepartmentId() != null) return dto.getDepartmentId();
        if (dto.getDepartmentIdentifier() != null && hospital != null) {
            Department department = hospital.getDepartments().stream()
                .filter(dep -> dep.getName().equalsIgnoreCase(dto.getDepartmentIdentifier()) ||
                               (dep.getCode() != null && dep.getCode().equalsIgnoreCase(dto.getDepartmentIdentifier())))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage("department.notfound", null, locale)));
            return department.getId();
        }
        throw new IllegalArgumentException(messageSource.getMessage("department.identifier.required", null, locale));
    }

    private final EncounterRepository encounterRepository;
    private final PatientRepository patientRepository;
    private final StaffRepository staffRepository;
    private final HospitalRepository hospitalRepository;
    private final AppointmentRepository appointmentRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    private final EncounterMapper encounterMapper;
    private final MessageSource messageSource;
    private final RoleValidator roleValidator;
    private final EncounterHistoryRepository encounterHistoryRepository;
    private final EncounterNoteRepository encounterNoteRepository;
    private final EncounterNoteAddendumRepository encounterNoteAddendumRepository;
    private final EncounterNoteHistoryRepository encounterNoteHistoryRepository;
    private final LabOrderRepository labOrderRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final ObgynReferralRepository obgynReferralRepository;
    private final UserRepository userRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

        private void recordHistory(Encounter encounter, String changeType, String changedBy, String previousValuesJson) {
            EncounterHistory history = EncounterHistory.builder()
                .encounterId(encounter.getId())
                .changedAt(java.time.LocalDateTime.now())
                .changedBy(changedBy)
                .encounterType(encounter.getEncounterType())
                .status(encounter.getStatus())
                .encounterDate(encounter.getEncounterDate())
                .notes(encounter.getNotes())
                .extraFieldsJson(encounter.getExtraFields() != null ? serializeMap(encounter.getExtraFields()) : null)
                .changeType(changeType)
                .previousValuesJson(previousValuesJson)
                .build();
            encounterHistoryRepository.save(history);
        }

        private String serializeMap(java.util.Map<String, Object> map) {
            try {
                return objectMapper.writeValueAsString(map);
            } catch (Exception e) {
                return null;
            }
        }

        private String serializeEncounter(Encounter encounter) {
            try {
                return objectMapper.writeValueAsString(encounter);
            } catch (Exception e) {
                return null;
            }
        }


    @Override
    @jakarta.transaction.Transactional
    public EncounterResponseDTO createEncounter(EncounterRequestDTO request, Locale locale) {
        // ---- 0) Service-side “one-of” validation (works even if you didn’t add the DTO-level validator) ----
        requireAtLeastOne("patientId or patientIdentifier", request.getPatientId(), request.getPatientIdentifier());
        requireAtLeastOne("staffId / staffIdentifier / staffEmail", request.getStaffId(), request.getStaffIdentifier(), request.getStaffEmail());
        requireAtLeastOne("hospitalId or hospitalIdentifier", request.getHospitalId(), request.getHospitalIdentifier());
        requireAtLeastOne("departmentId or departmentIdentifier", request.getDepartmentId(), request.getDepartmentIdentifier());

        if (request.getEncounterType() == null) {
            throw new BusinessException(messageSource.getMessage("encounter.type.required", null, locale));
        }

        EncounterResolution ctx = resolveEncounterResolution(request, locale);

        LocalDateTime encounterDate = Optional.ofNullable(request.getEncounterDate()).orElse(LocalDateTime.now());
        EncounterStatus status = Optional.ofNullable(request.getStatus()).orElse(EncounterStatus.ARRIVED);

        assertNoDuplicateEncounter(ctx, encounterDate, locale);

        Encounter encounter = encounterMapper.mergeEncounter(
            request,
            null,
            ctx.patient(),
            ctx.staff(),
            ctx.hospital(),
            ctx.appointment(),
            ctx.assignment());

        applyEncounterDefaults(encounter, ctx.department(), encounterDate, status);
        ensureEncounterCode(encounter);

        Encounter saved = encounterRepository.save(encounter);

        if (request.getNote() != null) {
            upsertEncounterNoteInternal(saved, ctx.staff(), request.getNote(), locale, request.getCreatedBy());
        }

        recordHistory(saved, "CREATED", resolveHistoryActor(request.getCreatedBy(), ctx.staff()), null);

        return encounterMapper.toEncounterResponseDTO(saved);
    }

    @Override
    @jakarta.transaction.Transactional(rollbackOn = Exception.class)
    public EncounterResponseDTO updateEncounter(UUID id, EncounterRequestDTO request, Locale locale) {
        Encounter existing = encounterRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(MSG_ENCOUNTER_NOT_FOUND, null, locale)));

        EncounterResolution ctx = resolveEncounterResolution(request, locale);
        // Keep a snapshot before merge for audit
        String previousValues = serializeEncounter(existing);

        Encounter merged = encounterMapper.mergeEncounter(
            request,
            existing,
            ctx.patient(),
            ctx.staff(),
            ctx.hospital(),
            ctx.appointment(),
            ctx.assignment());

        LocalDateTime fallbackDate = existing.getEncounterDate() != null ? existing.getEncounterDate() : LocalDateTime.now();
        EncounterStatus fallbackStatus = existing.getStatus() != null ? existing.getStatus() : EncounterStatus.ARRIVED;
        applyEncounterDefaults(merged, ctx.department(), fallbackDate, fallbackStatus);
        ensureEncounterCode(merged);

        Encounter saved = encounterRepository.save(merged);

        if (request.getNote() != null) {
            upsertEncounterNoteInternal(saved, ctx.staff(), request.getNote(), locale, request.getUpdatedBy());
        }

        recordHistory(saved, "UPDATED", resolveHistoryActor(request.getUpdatedBy(), ctx.staff()), previousValues);

        return encounterMapper.toEncounterResponseDTO(saved);
    }

    @Override
    @Transactional
    public void deleteEncounter(UUID id, Locale locale) {
        if (!encounterRepository.existsById(id)) {
            throw new ResourceNotFoundException(messageSource.getMessage(MSG_ENCOUNTER_NOT_FOUND, null, locale));
        }
        Encounter existing = encounterRepository.findById(id).orElse(null);
        encounterRepository.deleteById(id);
        if (existing != null) {
            // Record history for deletion
            recordHistory(existing, "DELETED", null, serializeEncounter(existing));
        }
    }

    @Override
    @Transactional
    public EncounterNoteResponseDTO upsertEncounterNote(UUID encounterId,
                                                        EncounterNoteRequestDTO request,
                                                        Locale locale) {
        if (request == null) {
            throw new BusinessException(messageSource.getMessage("encounter.note.payload.required", null, locale));
        }

        Encounter encounter = encounterRepository.findById(encounterId)
            .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(MSG_ENCOUNTER_NOT_FOUND, null, locale)));

        EncounterNote note = upsertEncounterNoteInternal(encounter, encounter.getStaff(), request, locale, null);
        return encounterMapper.toEncounterNoteResponseDTO(note);
    }

    @Override
    @Transactional
    public EncounterNoteAddendumResponseDTO addEncounterNoteAddendum(UUID encounterId,
                                                                     EncounterNoteAddendumRequestDTO request,
                                                                     Locale locale) {
        if (request == null) {
            throw new BusinessException(messageSource.getMessage("encounter.note.payload.required", null, locale));
        }

        Encounter encounter = encounterRepository.findById(encounterId)
            .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(MSG_ENCOUNTER_NOT_FOUND, null, locale)));

        EncounterNote note = encounterNoteRepository.findByEncounter_Id(encounterId)
            .orElseThrow(() -> new BusinessException(messageSource.getMessage("encounter.note.notfound", null, locale)));

        NoteAuthor author = resolveNoteAuthor(request.getAuthorStaffId(),
            request.getAuthorUserId(),
            request.getAuthorName(),
            encounter.getStaff(),
            locale);
        validateAuthorScope(encounter, author.staff(), locale);

        EncounterNoteAddendum addendum = EncounterNoteAddendum.builder()
            .note(note)
            .author(author.user())
            .authorStaff(author.staff())
            .authorName(resolveAuthorName(request.getAuthorName(), author))
            .authorCredentials(trimToNull(request.getAuthorCredentials()))
            .content(request.getContent())
            .eventOccurredAt(request.getEventOccurredAt())
            .documentedAt(request.getDocumentedAt() != null ? request.getDocumentedAt() : LocalDateTime.now())
            .signedAt(request.getSignedAt())
            .attestAccuracy(Boolean.TRUE.equals(request.getAttestAccuracy()))
            .attestNoAbbreviations(Boolean.TRUE.equals(request.getAttestNoAbbreviations()))
            .build();

        EncounterNoteAddendum savedAddendum = encounterNoteAddendumRepository.save(addendum);
        note.addAddendum(savedAddendum);
        encounterNoteRepository.save(note);
        recordNoteHistory(encounter, note, NOTE_HISTORY_ADDENDUM, author.actorIdentifier());

        return toAddendumResponse(savedAddendum);
    }

    @Override
    @Transactional
    public List<EncounterNoteHistoryResponseDTO> getEncounterNoteHistory(UUID encounterId, Locale locale) {
        if (!encounterRepository.existsById(encounterId)) {
            throw new ResourceNotFoundException(messageSource.getMessage(MSG_ENCOUNTER_NOT_FOUND, null, locale));
        }
        return encounterNoteHistoryRepository.findByEncounterIdOrderByChangedAtDesc(encounterId).stream()
            .map(encounterMapper::toEncounterNoteHistoryResponseDTO)
            .collect(Collectors.toList());
    }

    private EncounterNote upsertEncounterNoteInternal(Encounter encounter,
                                                      Staff defaultStaff,
                                                      EncounterNoteRequestDTO request,
                                                      Locale locale,
                                                      String actorOverride) {
        NoteAuthor author = resolveNoteAuthor(request.getAuthorStaffId(),
            request.getAuthorUserId(),
            request.getAuthorName(),
            defaultStaff,
            locale);
        validateAuthorScope(encounter, author.staff(), locale);

        EncounterNote note = Optional.ofNullable(encounter.getEncounterNote())
            .orElseGet(() -> encounterNoteRepository.findByEncounter_Id(encounter.getId()).orElse(new EncounterNote()));

        boolean isNew = note.getId() == null;
        if (isNew) {
            note.setEncounter(encounter);
            note.setPatient(encounter.getPatient());
            note.setHospital(encounter.getHospital());
            note.setDocumentedAt(request.getDocumentedAt() != null ? request.getDocumentedAt() : LocalDateTime.now());
        }

        applyNoteFields(note, request, author);

        if (shouldRebuildLinks(request)) {
            rebuildNoteLinks(note, request, encounter, locale);
        }

        EncounterNote saved = encounterNoteRepository.save(note);
        encounter.setEncounterNote(saved);

        String changedBy = trimToNull(actorOverride) != null ? trimToNull(actorOverride) : author.actorIdentifier();
        recordNoteHistory(encounter, saved, isNew ? NOTE_HISTORY_CREATED : NOTE_HISTORY_UPDATED, changedBy);
        return saved;
    }

    private EncounterResolution resolveEncounterResolution(EncounterRequestDTO request, Locale locale) {
        UUID patientId = resolvePatientId(request, locale);
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(MSG_PATIENT_NOT_FOUND, null, locale)));

        UUID staffId = resolveStaffId(request, locale);
        Staff staff = staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(MSG_STAFF_NOT_FOUND, null, locale)));

        UUID hospitalId = resolveHospitalId(request, locale);
        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(MSG_HOSPITAL_NOT_FOUND, null, locale)));

        ensureStaffHospitalAlignment(staff, hospitalId, locale);

        Appointment appointment = findAppointment(request.getAppointmentId(), locale);
        UUID departmentId = resolveDepartmentId(request, hospital, locale);
        Department department = findDepartmentInHospital(hospital, departmentId);

        UUID userId = Optional.ofNullable(staff.getUser())
            .map(User::getId)
            .orElseThrow(() -> new BusinessException(messageSource.getMessage(MSG_ENCOUNTER_STAFF_INVALID, null, locale)));

        validateStaffRole(userId, hospitalId, locale);
        validateAppointment(appointment, patient, hospitalId, locale);

        UserRoleHospitalAssignment assignment = assignmentRepository
            .findByUserIdAndHospitalId(userId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(MSG_ASSIGNMENT_NOT_FOUND, null, locale)));

        return new EncounterResolution(patient, staff, hospital, appointment, department, assignment, hospitalId, userId);
    }

    private Appointment findAppointment(UUID appointmentId, Locale locale) {
        if (appointmentId == null) {
            return null;
        }
        return appointmentRepository.findById(appointmentId)
            .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage("appointment.notfound", null, locale)));
    }

    private Department findDepartmentInHospital(Hospital hospital, UUID departmentId) {
        if (hospital == null || departmentId == null || hospital.getDepartments() == null) {
            return null;
        }
        return hospital.getDepartments().stream()
            .filter(dep -> dep.getId().equals(departmentId))
            .findFirst()
            .orElse(null);
    }

    private void ensureStaffHospitalAlignment(Staff staff, UUID hospitalId, Locale locale) {
        if (staff.getHospital() == null || !hospitalId.equals(staff.getHospital().getId())) {
            throw new BusinessException(messageSource.getMessage(MSG_ENCOUNTER_STAFF_HOSPITAL_MISMATCH, null, locale));
        }
    }

    private void assertNoDuplicateEncounter(EncounterResolution ctx, LocalDateTime encounterDate, Locale locale) {
        boolean exists = ctx.appointment() != null
            ? encounterRepository.existsByHospital_IdAndPatient_IdAndAppointment_IdAndEncounterDate(
                ctx.hospitalId(), ctx.patient().getId(), ctx.appointment().getId(), encounterDate)
            : encounterRepository.existsByHospital_IdAndPatient_IdAndEncounterDate(
                ctx.hospitalId(), ctx.patient().getId(), encounterDate);
        if (exists) {
            throw new BusinessException(messageSource.getMessage("encounter.duplicate", null, locale));
        }
    }

    private void applyEncounterDefaults(Encounter encounter,
                                        Department department,
                                        LocalDateTime fallbackDate,
                                        EncounterStatus fallbackStatus) {
        if (department != null) {
            encounter.setDepartment(department);
        }
        if (encounter.getEncounterDate() == null) {
            encounter.setEncounterDate(fallbackDate != null ? fallbackDate : LocalDateTime.now());
        }
        if (encounter.getStatus() == null) {
            encounter.setStatus(fallbackStatus != null ? fallbackStatus : EncounterStatus.ARRIVED);
        }
    }

    private void ensureEncounterCode(Encounter encounter) {
        if (encounter.getCode() == null || encounter.getCode().isBlank()) {
            encounter.setCode(encounter.generateEncounterCode());
        }
    }

    private String resolveHistoryActor(String override, Staff staff) {
        String fromOverride = trimToNull(override);
        if (fromOverride != null) {
            return fromOverride;
        }
        if (staff != null && staff.getUser() != null) {
            return staff.getUser().getEmail();
        }
        return null;
    }

    private void applyNoteFields(EncounterNote note, EncounterNoteRequestDTO request, NoteAuthor author) {
        applyAuthorMetadata(note, author, request);
        applyTemplateSelection(note, request);
        applyTextSections(note, request);
        applyTimingMetadata(note, request);
        applyAttestations(note, request);
        applySignature(note, request);
    }

    private void applyAuthorMetadata(EncounterNote note, NoteAuthor author, EncounterNoteRequestDTO request) {
        note.setAuthor(author.user());
        note.setAuthorStaff(author.staff());
        if (author.displayName() != null) {
            note.setAuthorName(author.displayName());
        }
        setStringIfPresent(request.getAuthorCredentials(), value -> note.setAuthorCredentials(trimToNull(value)));
    }

    private void applyTemplateSelection(EncounterNote note, EncounterNoteRequestDTO request) {
        if (request.getTemplate() != null) {
            note.setTemplate(request.getTemplate());
        } else if (note.getTemplate() == null) {
            note.setTemplate(EncounterNoteTemplate.SOAP);
        }
    }

    private void applyTextSections(EncounterNote note, EncounterNoteRequestDTO request) {
        NOTE_TEXT_FIELDS.forEach(mapping -> {
            String value = trimToNull(mapping.requestExtractor().apply(request));
            if (value != null) {
                mapping.noteSetter().accept(note, value);
            }
        });
    }

    private void applyTimingMetadata(EncounterNote note, EncounterNoteRequestDTO request) {
        if (request.getLateEntry() != null) {
            note.setLateEntry(Boolean.TRUE.equals(request.getLateEntry()));
        } else if (note.getId() == null) {
            note.setLateEntry(false);
        }
        if (request.getEventOccurredAt() != null || Boolean.FALSE.equals(request.getLateEntry())) {
            note.setEventOccurredAt(request.getEventOccurredAt());
        }
        if (request.getDocumentedAt() != null) {
            note.setDocumentedAt(request.getDocumentedAt());
        } else if (note.getDocumentedAt() == null) {
            note.setDocumentedAt(LocalDateTime.now());
        }
    }

    private void applyAttestations(EncounterNote note, EncounterNoteRequestDTO request) {
        if (request.getAttestAccuracy() != null) {
            note.setAttestAccuracy(Boolean.TRUE.equals(request.getAttestAccuracy()));
        }
        if (request.getAttestNoAbbreviations() != null) {
            note.setAttestNoAbbreviations(Boolean.TRUE.equals(request.getAttestNoAbbreviations()));
        }
        if (request.getAttestSpellCheck() != null) {
            note.setAttestSpellCheck(Boolean.TRUE.equals(request.getAttestSpellCheck()));
        }
    }

    private void applySignature(EncounterNote note, EncounterNoteRequestDTO request) {
        if (request.getSignedAt() != null) {
            note.setSignedAt(request.getSignedAt());
        }
        setStringIfPresent(request.getSignedByName(), value -> note.setSignedByName(trimToNull(value)));
        setStringIfPresent(request.getSignedByCredentials(), value -> note.setSignedByCredentials(trimToNull(value)));
    }

    private boolean shouldRebuildLinks(EncounterNoteRequestDTO request) {
        return request.getCustomLinks() != null
            || request.getLinkedLabOrderIds() != null
            || request.getLinkedPrescriptionIds() != null
            || request.getLinkedReferralIds() != null;
    }

    private void rebuildNoteLinks(EncounterNote note,
                                  EncounterNoteRequestDTO request,
                                  Encounter encounter,
                                  Locale locale) {
        resetNoteLinks(note);
        addCustomLinks(note, request);
        addLabOrderLinks(note, request, encounter, locale);
        addPrescriptionLinks(note, request, encounter, locale);
        addReferralLinks(note, request, encounter, locale);
    }

    private void resetNoteLinks(EncounterNote note) {
        note.clearLinks();
    }

    private void addCustomLinks(EncounterNote note, EncounterNoteRequestDTO request) {
        if (request.getCustomLinks() == null) {
            return;
        }
        request.getCustomLinks().stream()
            .filter(Objects::nonNull)
            .forEach(linkRequest -> note.addLink(EncounterNoteLink.builder()
                .artifactType(linkRequest.getArtifactType())
                .artifactId(linkRequest.getArtifactId())
                .artifactCode(trimToNull(linkRequest.getArtifactCode()))
                .artifactDisplay(trimToNull(linkRequest.getArtifactDisplay()))
                .artifactStatus(trimToNull(linkRequest.getArtifactStatus()))
                .linkedAt(LocalDateTime.now())
                .build()));
    }

    private void addLabOrderLinks(EncounterNote note,
                                  EncounterNoteRequestDTO request,
                                  Encounter encounter,
                                  Locale locale) {
        if (request.getLinkedLabOrderIds() == null) {
            return;
        }
        for (UUID id : request.getLinkedLabOrderIds()) {
            if (id == null) {
                continue;
            }
            LabOrder order = labOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage("laborder.notfound", null, locale)));
            validateArtifactScope(encounter, order.getPatient().getId(), order.getHospital().getId(), locale);
            note.addLink(EncounterNoteLink.builder()
                .artifactType(EncounterNoteLinkType.LAB_ORDER)
                .artifactId(order.getId())
                .artifactCode(order.getLabTestDefinition() != null ? order.getLabTestDefinition().getTestCode() : null)
                .artifactDisplay(order.getLabTestDefinition() != null ? order.getLabTestDefinition().getName() : order.getClinicalIndication())
                .artifactStatus(order.getStatus() != null ? order.getStatus().name() : null)
                .linkedAt(LocalDateTime.now())
                .build());
        }
    }

    private void addPrescriptionLinks(EncounterNote note,
                                      EncounterNoteRequestDTO request,
                                      Encounter encounter,
                                      Locale locale) {
        if (request.getLinkedPrescriptionIds() == null) {
            return;
        }
        for (UUID id : request.getLinkedPrescriptionIds()) {
            if (id == null) {
                continue;
            }
            Prescription prescription = prescriptionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage("prescription.notfound", null, locale)));
            validateArtifactScope(encounter, prescription.getPatient().getId(), prescription.getHospital().getId(), locale);
            note.addLink(EncounterNoteLink.builder()
                .artifactType(EncounterNoteLinkType.PRESCRIPTION)
                .artifactId(prescription.getId())
                .artifactCode(trimToNull(prescription.getMedicationCode()))
                .artifactDisplay(trimToNull(prescription.getMedicationName()))
                .artifactStatus(prescription.getStatus() != null ? prescription.getStatus().name() : null)
                .linkedAt(LocalDateTime.now())
                .build());
        }
    }

    private void addReferralLinks(EncounterNote note,
                                  EncounterNoteRequestDTO request,
                                  Encounter encounter,
                                  Locale locale) {
        if (request.getLinkedReferralIds() == null) {
            return;
        }
        for (UUID id : request.getLinkedReferralIds()) {
            if (id == null) {
                continue;
            }
            ObgynReferral referral = obgynReferralRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage("obgyn.referral.notfound", null, locale)));
            validateArtifactScope(encounter, referral.getPatient().getId(), referral.getHospital().getId(), locale);
            note.addLink(EncounterNoteLink.builder()
                .artifactType(EncounterNoteLinkType.REFERRAL)
                .artifactId(referral.getId())
                .artifactCode(referral.getCareContext() != null ? referral.getCareContext().name() : null)
                .artifactDisplay(trimToNull(referral.getReferralReason()))
                .artifactStatus(referral.getStatus() != null ? referral.getStatus().name() : null)
                .linkedAt(LocalDateTime.now())
                .build());
        }
    }

    private void recordNoteHistory(Encounter encounter,
                                   EncounterNote note,
                                   String changeType,
                                   String changedBy) {
        if (note == null) {
            return;
        }
        EncounterNoteHistory history = EncounterNoteHistory.builder()
            .encounterId(encounter.getId())
            .noteId(note.getId())
            .template(note.getTemplate())
            .changedAt(LocalDateTime.now())
            .changedBy(changedBy)
            .changeType(changeType)
            .contentSnapshot(buildNoteContentSnapshot(note))
            .metadataSnapshot(buildNoteMetadataSnapshot(note))
            .build();
        encounterNoteHistoryRepository.save(history);
    }

    private String buildNoteContentSnapshot(EncounterNote note) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("template", note.getTemplate());
        snapshot.put("chiefComplaint", note.getChiefComplaint());
        snapshot.put("historyOfPresentIllness", note.getHistoryOfPresentIllness());
        snapshot.put("reviewOfSystems", note.getReviewOfSystems());
        snapshot.put("physicalExam", note.getPhysicalExam());
        snapshot.put("diagnosticResults", note.getDiagnosticResults());
        snapshot.put("subjective", note.getSubjective());
        snapshot.put("objective", note.getObjective());
        snapshot.put("assessment", note.getAssessment());
        snapshot.put("plan", note.getPlan());
        snapshot.put("implementation", note.getImplementation());
        snapshot.put("evaluation", note.getEvaluation());
        snapshot.put("patientInstructions", note.getPatientInstructions());
        snapshot.put("summary", note.getSummary());
        snapshot.put("addenda", note.getAddenda() != null ? note.getAddenda().size() : 0);
        return toJson(snapshot);
    }

    private String buildNoteMetadataSnapshot(EncounterNote note) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("authorUserId", note.getAuthor() != null ? note.getAuthor().getId() : null);
        snapshot.put("authorStaffId", note.getAuthorStaff() != null ? note.getAuthorStaff().getId() : null);
        snapshot.put("authorName", note.getAuthorName());
        snapshot.put("authorCredentials", note.getAuthorCredentials());
        snapshot.put("lateEntry", note.isLateEntry());
        snapshot.put("eventOccurredAt", note.getEventOccurredAt());
        snapshot.put("documentedAt", note.getDocumentedAt());
        snapshot.put("attestAccuracy", note.isAttestAccuracy());
        snapshot.put("attestNoAbbreviations", note.isAttestNoAbbreviations());
        snapshot.put("attestSpellCheck", note.isAttestSpellCheck());
        snapshot.put("signedAt", note.getSignedAt());
        snapshot.put("signedByName", note.getSignedByName());
        snapshot.put("signedByCredentials", note.getSignedByCredentials());
        snapshot.put("linkedArtifacts", note.getLinks() != null
            ? note.getLinks().stream().map(EncounterNoteLink::getArtifactId).toList()
            : Collections.emptyList());
        return toJson(snapshot);
    }

    private NoteAuthor resolveNoteAuthor(UUID requestedStaffId,
                                         UUID requestedUserId,
                                         String requestedDisplayName,
                                         Staff defaultStaff,
                                         Locale locale) {
        Staff staff = resolveAuthorStaff(requestedStaffId, defaultStaff, locale);
        User user = resolveAuthorUser(requestedUserId, staff, locale);
        String displayName = trimToNull(requestedDisplayName);
        if (displayName == null && user != null) {
            displayName = joinName(user.getFirstName(), user.getLastName());
        }
        if (displayName == null && staff != null && staff.getUser() != null) {
            displayName = joinName(staff.getUser().getFirstName(), staff.getUser().getLastName());
        }
        String actorIdentifier = determineActorIdentifier(displayName, user, staff, defaultStaff);
        return new NoteAuthor(user, staff, displayName, actorIdentifier);
    }

    private Staff resolveAuthorStaff(UUID requestedStaffId, Staff defaultStaff, Locale locale) {
        if (requestedStaffId == null) {
            return defaultStaff;
        }
        return staffRepository.findById(requestedStaffId)
            .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(MSG_STAFF_NOT_FOUND, null, locale)));
    }

    private User resolveAuthorUser(UUID requestedUserId, Staff authorStaff, Locale locale) {
        if (requestedUserId != null) {
            return userRepository.findById(requestedUserId)
                .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage("user.notfound", null, locale)));
        }
        if (authorStaff != null && authorStaff.getUser() != null) {
            return authorStaff.getUser();
        }
        throw new BusinessException(messageSource.getMessage("encounter.note.author.required", null, locale));
    }

    private String determineActorIdentifier(String displayName,
                                            User authorUser,
                                            Staff authorStaff,
                                            Staff fallbackStaff) {
        if (authorUser != null && authorUser.getEmail() != null) {
            return authorUser.getEmail();
        }
        if (displayName != null) {
            return displayName;
        }
        if (authorStaff != null && authorStaff.getUser() != null && authorStaff.getUser().getEmail() != null) {
            return authorStaff.getUser().getEmail();
        }
        if (fallbackStaff != null && fallbackStaff.getUser() != null && fallbackStaff.getUser().getEmail() != null) {
            return fallbackStaff.getUser().getEmail();
        }
        return null;
    }

    private void validateAuthorScope(Encounter encounter, Staff authorStaff, Locale locale) {
        if (authorStaff == null) {
            return;
        }
        if (authorStaff.getHospital() == null || encounter.getHospital() == null
            || !Objects.equals(authorStaff.getHospital().getId(), encounter.getHospital().getId())) {
            throw new BusinessException(messageSource.getMessage(MSG_ENCOUNTER_STAFF_HOSPITAL_MISMATCH, null, locale));
        }
    }

    private void validateArtifactScope(Encounter encounter,
                                       UUID artifactPatientId,
                                       UUID artifactHospitalId,
                                       Locale locale) {
        if (!Objects.equals(encounter.getPatient().getId(), artifactPatientId)
            || !Objects.equals(encounter.getHospital().getId(), artifactHospitalId)) {
            throw new BusinessException(messageSource.getMessage("encounter.note.link.scope", null, locale));
        }
    }

    private EncounterNoteAddendumResponseDTO toAddendumResponse(EncounterNoteAddendum addendum) {
        return EncounterNoteAddendumResponseDTO.builder()
            .id(addendum.getId())
            .content(addendum.getContent())
            .eventOccurredAt(addendum.getEventOccurredAt())
            .documentedAt(addendum.getDocumentedAt())
            .signedAt(addendum.getSignedAt())
            .authorName(addendum.getAuthorName())
            .authorCredentials(addendum.getAuthorCredentials())
            .attestAccuracy(addendum.isAttestAccuracy())
            .attestNoAbbreviations(addendum.isAttestNoAbbreviations())
            .build();
    }

    private String resolveAuthorName(String requestedName, NoteAuthor author) {
        String name = trimToNull(requestedName);
        if (name != null) {
            return name;
        }
        if (author.displayName() != null) {
            return author.displayName();
        }
        return null;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void setStringIfPresent(String value, Consumer<String> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private record NoteAuthor(User user,
                              Staff staff,
                              String displayName,
                              String actorIdentifier) {}

    private record EncounterResolution(Patient patient,
                                       Staff staff,
                                       Hospital hospital,
                                       Appointment appointment,
                                       Department department,
                                       UserRoleHospitalAssignment assignment,
                                       UUID hospitalId,
                                       UUID userId) {}

    private record TextFieldMapping(Function<EncounterNoteRequestDTO, String> requestExtractor,
                                    BiConsumer<EncounterNote, String> noteSetter) {}

    @Override
    @Transactional
    public EncounterResponseDTO getEncounterById(UUID id, Locale locale) {
        Encounter e = encounterRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(MSG_ENCOUNTER_NOT_FOUND, null, locale)));
        return encounterMapper.toEncounterResponseDTO(e);
    }


    @Override
    @Transactional
    public List<EncounterResponseDTO> getEncountersByDoctorId(UUID staffId, Locale locale) {
        Staff staff = staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(MSG_STAFF_NOT_FOUND, null, locale)));

        if (staff.getUser() == null || !roleValidator.isDoctor(staff.getUser().getId(), null)) {
            throw new BusinessException(messageSource.getMessage(MSG_ENCOUNTER_STAFF_INVALID, null, locale));
        }

        return encounterRepository.findByStaff_Id(staffId).stream()
            .map(encounterMapper::toEncounterResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public Page<EncounterResponseDTO> list(UUID patientId,
                                           UUID staffId,
                                           UUID hospitalId,
                                           LocalDateTime from,
                                           LocalDateTime to,
                                           EncounterStatus status,
                                           Pageable pageable,
                                           Locale locale) {
        Specification<Encounter> spec = alwaysTrue();

        if (patientId != null)  spec = spec.and(eqUuid("patient.id", patientId));
        if (staffId != null)    spec = spec.and(eqUuid("staff.id", staffId));
        if (hospitalId != null) spec = spec.and(eqUuid("hospital.id", hospitalId));
        if (from != null)       spec = spec.and(ge("encounterDate", from));
        if (to != null)         spec = spec.and(le("encounterDate", to));
        if (status != null)     spec = spec.and(eqEnum("status", status));

        return encounterRepository.findAll(spec, pageable)
            .map(encounterMapper::toEncounterResponseDTO);
    }

    /** Always-true base spec to keep chaining safe & typed. */
    private static Specification<Encounter> alwaysTrue() {
        return (root, q, cb) -> cb.conjunction();
    }

    private static Specification<Encounter> eqUuid(String path, UUID value) {
        return (root, q, cb) -> cb.equal(getPath(root, path), value);
    }

    private static <E extends Enum<E>> Specification<Encounter> eqEnum(String path, E value) {
        return (root, q, cb) -> cb.equal(getPath(root, path), value);
    }

    private static Specification<Encounter> ge(String path, LocalDateTime value) {
        return (root, q, cb) -> cb.greaterThanOrEqualTo(getPath(root, path), value);
    }
    private static Specification<Encounter> le(String path, LocalDateTime value) {
        return (root, q, cb) -> cb.lessThanOrEqualTo(getPath(root, path), value);
    }

    @SuppressWarnings("unchecked")
    private static <X> Path<X> getPath(From<?, ?> root, String dotPath) {
        Path<?> p = root;
        for (String part : dotPath.split("\\.")) {
            p = p.get(part);
        }
        return (Path<X>) p;
    }

    public EncounterTreatmentResponseDTO toDto(EncounterTreatment entity) {
        if (entity == null) return null;

        String staffFullName = null;
        if (entity.getStaff() != null && entity.getStaff().getUser() != null) {
            staffFullName = joinName(entity.getStaff().getUser().getFirstName(),
                entity.getStaff().getUser().getLastName());
        } else if (entity.getStaff() != null) {
            staffFullName = entity.getStaff().getName();
        }

        return EncounterTreatmentResponseDTO.builder()
            .id(entity.getId())
            .encounterId(entity.getEncounter() != null ? entity.getEncounter().getId() : null)
            .encounterType(entity.getEncounter() != null ? entity.getEncounter().getEncounterType() : null)
            .patientId(entity.getEncounter() != null && entity.getEncounter().getPatient() != null
                ? entity.getEncounter().getPatient().getId() : null)
            .patientFullName(entity.getEncounter() != null && entity.getEncounter().getPatient() != null
                ? joinName(entity.getEncounter().getPatient().getFirstName(),
                entity.getEncounter().getPatient().getLastName())
                : null)
            .patientPhoneNumber(entity.getEncounter() != null && entity.getEncounter().getPatient() != null
                ? entity.getEncounter().getPatient().getPhoneNumberPrimary() : null)
            .treatmentId(entity.getTreatment() != null ? entity.getTreatment().getId() : null)
            .treatmentName(entity.getTreatment() != null ? entity.getTreatment().getName() : null)
            .staffId(entity.getStaff() != null ? entity.getStaff().getId() : null)
            .staffFullName(staffFullName)
            .performedAt(entity.getPerformedAt())
            .outcome(entity.getOutcome())
            .notes(entity.getNotes())
            .build();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<EncounterResponseDTO> getEncountersByPatientIdentifier(String identifier, Locale locale) {
        Patient patient = patientRepository.findByUsernameOrEmail(identifier)
            .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(MSG_PATIENT_NOT_FOUND, null, locale)));

        List<Encounter> encounters = encounterRepository.findByPatient_Id(patient.getId());

        return encounters.stream()
            .map(encounterMapper::toEncounterResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<EncounterResponseDTO> getEncountersByPatientId(UUID patientId, Locale locale) {
        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException(messageSource.getMessage(MSG_PATIENT_NOT_FOUND, null, locale));
        }
        return encounterRepository.findByPatient_Id(patientId).stream()
            .map(encounterMapper::toEncounterResponseDTO)
            .collect(Collectors.toList());
    }

    private void requireAtLeastOne(String label, Object... values) {
        for (Object v : values) {
            if (v == null) continue;
            if (v instanceof CharSequence s) {
                if (!s.toString().trim().isEmpty()) return;
            } else {
                return;
            }
        }
        throw new BusinessException("Provide " + label + ".");
    }


}
