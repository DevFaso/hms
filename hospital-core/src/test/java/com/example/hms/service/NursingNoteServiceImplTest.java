package com.example.hms.service;

import com.example.hms.enums.JobTitle;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.NursingNoteMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.NursingNote;
import com.example.hms.model.NursingNoteTemplate;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
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
import com.example.hms.service.impl.NursingNoteServiceImpl;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NursingNoteServiceImplTest {

    @Mock
    private NursingNoteRepository nursingNoteRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private PatientHospitalRegistrationRepository registrationRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MessageSource messageSource;
    @Mock
    private RoleValidator roleValidator;
    @Mock
    private AuditEventLogService auditEventLogService;
    @Mock
    private NursingNoteMapper nursingNoteMapper;

    @InjectMocks
    private NursingNoteServiceImpl nursingNoteService;

    @Test
    void createNote_persistsStructuredNoteAndLogsAudit() {
        UUID actorUserId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        Locale locale = Locale.US;

        NursingNoteCreateRequestDTO request = NursingNoteCreateRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .template(NursingNoteTemplate.DAR)
            .dataSubjective("Patient complained of acute pain")
            .narrative("Documented initial assessment")
            .attestAccuracy(true)
            .attestSpellCheck(true)
            .attestNoAbbreviations(true)
            .readabilityScore(72.5)
            .signAndComplete(true)
            .signedByName("Nurse Joy")
            .signedByCredentials("RN")
            .educationEntries(List.of(
                NursingNoteEducationDTO.builder()
                    .topic("Pain management")
                    .patientUnderstanding("Verbalized understanding")
                    .teachingMethod("Teach-back")
                    .build()
            ))
            .interventionEntries(List.of(
                NursingNoteInterventionDTO.builder()
                    .description("Administered prescribed analgesic")
                    .followUpActions("Reassess in 30 minutes")
                    .build()
            ))
            .build();
        Patient patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("John");
        patient.setLastName("Doe");

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("General Hospital");

        User author = new User();
        author.setId(actorUserId);
        author.setFirstName("Nurse");
        author.setLastName("Joy");

        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(assignmentId);

        Staff staff = new Staff();
        staff.setId(staffId);
        staff.setAssignment(assignment);
        staff.setJobTitle(JobTitle.NURSE);
        staff.setLicenseNumber("RN-12345");
        staff.setUser(author);
        staff.setHospital(hospital);
        assignment.setHospital(hospital);

        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setId(UUID.randomUUID());
        registration.setActive(true);

        NursingNoteResponseDTO mappedResponse = NursingNoteResponseDTO.builder()
            .id(noteId)
            .patientId(patientId)
            .hospitalId(hospitalId)
            .build();
        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(true);

        when(userRepository.findById(actorUserId)).thenReturn(Optional.of(author));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(registration));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findByUserIdAndHospitalId(actorUserId, hospitalId)).thenReturn(Optional.of(staff));

        when(nursingNoteRepository.save(any(NursingNote.class))).thenAnswer(invocation -> {
            NursingNote note = invocation.getArgument(0);
            note.setId(noteId);
            note.setCreatedAt(LocalDateTime.now());
            note.setUpdatedAt(LocalDateTime.now());
            return note;
        });
        when(nursingNoteMapper.toResponse(any(NursingNote.class))).thenReturn(mappedResponse);

        NursingNoteResponseDTO response = nursingNoteService.createNote(request, locale);

        assertNotNull(response);
        assertEquals(noteId, response.getId());

        ArgumentCaptor<NursingNote> captor = ArgumentCaptor.forClass(NursingNote.class);
        verify(nursingNoteRepository).save(captor.capture());
        NursingNote saved = captor.getValue();
        assertEquals(patientId, saved.getPatient().getId());
        assertEquals(hospitalId, saved.getHospital().getId());
        assertEquals(author.getId(), saved.getAuthor().getId());
        assertEquals(1, saved.getEducationEntries().size());
        assertEquals(1, saved.getInterventionEntries().size());
        verify(auditEventLogService).logEvent(any(AuditEventRequestDTO.class));
    }

    @Test
    void createNote_allowsMidwifeToDocument() {
        UUID actorUserId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();

        NursingNoteCreateRequestDTO request = NursingNoteCreateRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .template(NursingNoteTemplate.DAR)
            .narrative("Midwife documented care")
            .attestAccuracy(true)
            .attestNoAbbreviations(true)
            .attestSpellCheck(true)
            .educationEntries(List.of())
            .interventionEntries(List.of())
            .build();

        Patient patient = new Patient();
        patient.setId(patientId);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Birthing Center");
        User author = new User();
        author.setId(actorUserId);
        author.setFirstName("Morgan");
        author.setLastName("Lee");

        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(hospital);

        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setAssignment(assignment);
        staff.setUser(author);
        staff.setHospital(hospital);

        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(false);
        when(roleValidator.isMidwife(actorUserId, hospitalId)).thenReturn(true);

        when(userRepository.findById(actorUserId)).thenReturn(Optional.of(author));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findByUserIdAndHospitalId(actorUserId, hospitalId)).thenReturn(Optional.of(staff));
        when(nursingNoteRepository.save(any(NursingNote.class))).thenAnswer(invocation -> {
            NursingNote note = invocation.getArgument(0);
            note.setId(noteId);
            note.setHospital(hospital);
            return note;
        });
        when(nursingNoteMapper.toResponse(any(NursingNote.class))).thenReturn(NursingNoteResponseDTO.builder()
            .id(noteId)
            .patientId(patientId)
            .hospitalId(hospitalId)
            .build());

        NursingNoteResponseDTO response = nursingNoteService.createNote(request, Locale.US);

        assertNotNull(response);
        assertEquals(noteId, response.getId());
        verify(roleValidator).isMidwife(actorUserId, hospitalId);
        verify(auditEventLogService).logEvent(any(AuditEventRequestDTO.class));
    }

    @Test
    void createNote_calculatesReadabilityScoreWhenMissing() {
        UUID actorUserId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();

        NursingNoteCreateRequestDTO request = NursingNoteCreateRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .template(NursingNoteTemplate.DAR)
            .narrative("Patient resting comfortably. Vital signs stable.")
            .actionSummary("Administered scheduled medication. Continued monitoring.")
            .responseSummary("Patient denies pain post medication.")
            .educationSummary("Reinforced fall precautions.")
            .attestAccuracy(true)
            .attestSpellCheck(true)
            .attestNoAbbreviations(true)
            .signAndComplete(false)
            .educationEntries(List.of())
            .interventionEntries(List.of())
            .build();

        Patient patient = new Patient();
        patient.setId(patientId);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        User author = new User();
        author.setId(actorUserId);

        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(true);
        when(userRepository.findById(actorUserId)).thenReturn(Optional.of(author));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findByUserIdAndHospitalId(actorUserId, hospitalId)).thenReturn(Optional.empty());
        when(nursingNoteRepository.save(any(NursingNote.class))).thenAnswer(invocation -> {
            NursingNote note = invocation.getArgument(0);
            note.setId(noteId);
            return note;
        });
        when(nursingNoteMapper.toResponse(any(NursingNote.class))).thenReturn(NursingNoteResponseDTO.builder()
            .id(noteId)
            .patientId(patientId)
            .hospitalId(hospitalId)
            .build());

        nursingNoteService.createNote(request, Locale.US);

        ArgumentCaptor<NursingNote> captor = ArgumentCaptor.forClass(NursingNote.class);
        verify(nursingNoteRepository).save(captor.capture());
        NursingNote saved = captor.getValue();
    assertNotNull(saved.getReadabilityScore());
    }

    @Test
    void createNote_ignoresEmptyStructuredEntries() {
        UUID actorUserId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();

        NursingNoteEducationDTO emptyEducation = NursingNoteEducationDTO.builder().build();
        NursingNoteEducationDTO validEducation = NursingNoteEducationDTO.builder()
            .topic("Dietary guidance")
            .patientUnderstanding("Needs reinforcement")
            .build();
    NursingNoteInterventionDTO emptyIntervention = NursingNoteInterventionDTO.builder().build();
        NursingNoteInterventionDTO validIntervention = NursingNoteInterventionDTO.builder()
            .description("Coordinated physical therapy consult")
            .followUpActions("Review progress tomorrow")
            .build();

        NursingNoteCreateRequestDTO request = NursingNoteCreateRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .template(NursingNoteTemplate.DAR)
            .narrative("Follow-up assessment documented.")
            .actionSummary("Coordinated referrals.")
            .responseSummary("Patient verbalized understanding of plan.")
            .educationSummary("Discussed discharge instructions.")
            .attestAccuracy(true)
            .attestSpellCheck(true)
            .attestNoAbbreviations(true)
            .educationEntries(Arrays.asList(emptyEducation, validEducation))
            .interventionEntries(Arrays.asList(emptyIntervention, validIntervention))
            .build();

        Patient patient = new Patient();
        patient.setId(patientId);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        User author = new User();
        author.setId(actorUserId);

        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(true);
        when(userRepository.findById(actorUserId)).thenReturn(Optional.of(author));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findByUserIdAndHospitalId(actorUserId, hospitalId)).thenReturn(Optional.empty());
        when(nursingNoteRepository.save(any(NursingNote.class))).thenAnswer(invocation -> {
            NursingNote note = invocation.getArgument(0);
            note.setId(noteId);
            return note;
        });
        when(nursingNoteMapper.toResponse(any(NursingNote.class))).thenReturn(NursingNoteResponseDTO.builder()
            .id(noteId)
            .patientId(patientId)
            .hospitalId(hospitalId)
            .build());

        nursingNoteService.createNote(request, Locale.US);

        ArgumentCaptor<NursingNote> captor = ArgumentCaptor.forClass(NursingNote.class);
        verify(nursingNoteRepository).save(captor.capture());
        NursingNote saved = captor.getValue();
        assertEquals(1, saved.getEducationEntries().size());
        assertEquals("Dietary guidance", saved.getEducationEntries().get(0).getTopic());
        assertEquals(1, saved.getInterventionEntries().size());
        assertEquals("Coordinated physical therapy consult", saved.getInterventionEntries().get(0).getDescription());
    }

    @Test
    void createNote_throwsWhenPatientNotRegistered() {
        UUID actorUserId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Locale locale = Locale.CANADA;

        NursingNoteCreateRequestDTO request = NursingNoteCreateRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .template(NursingNoteTemplate.DAR)
            .attestAccuracy(true)
            .attestSpellCheck(true)
            .attestNoAbbreviations(true)
            .educationEntries(List.of(
                NursingNoteEducationDTO.builder()
                    .topic("Diet").patientUnderstanding("Understands").build()
            ))
            .interventionEntries(List.of(
                NursingNoteInterventionDTO.builder().description("Provided dietary counseling").build()
            ))
            .build();

        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(true);

        User author = new User();
        author.setId(actorUserId);
        Patient patient = new Patient();
        patient.setId(patientId);

        when(userRepository.findById(actorUserId)).thenReturn(Optional.of(author));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.empty());
        when(messageSource.getMessage(eq("patient.notRegisteredInHospital"), any(), anyString(), any(Locale.class)))
            .thenReturn("Patient not registered");

        assertThrows(BusinessException.class, () -> nursingNoteService.createNote(request, locale));
        verifyNoInteractions(nursingNoteRepository);
        verifyNoInteractions(auditEventLogService);
    }

    @Test
    void createNote_throwsWhenAuthorMissing() {
        UUID actorUserId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        NursingNoteCreateRequestDTO request = NursingNoteCreateRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .build();

        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(true);
        when(userRepository.findById(actorUserId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> nursingNoteService.createNote(request, Locale.US));
        verifyNoInteractions(nursingNoteRepository);
    }

    @Test
    void createNote_throwsWhenPatientMissingUsesMessageSource() {
        UUID actorUserId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        NursingNoteCreateRequestDTO request = NursingNoteCreateRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .build();

        User author = new User();
        author.setId(actorUserId);

        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(true);
        when(userRepository.findById(actorUserId)).thenReturn(Optional.of(author));
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        when(messageSource.getMessage(eq("patient.notFound"), any(), anyString(), any(Locale.class)))
            .thenReturn("patient missing");

        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
            () -> nursingNoteService.createNote(request, Locale.CANADA));
        assertTrue(exception.getMessage().contains("patient missing"));
        verifyNoInteractions(nursingNoteRepository);
    }

    @Test
    void createNote_throwsWhenHospitalMissing() {
        UUID actorUserId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        NursingNoteCreateRequestDTO request = NursingNoteCreateRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .build();

        User author = new User();
        author.setId(actorUserId);
        Patient patient = new Patient();
        patient.setId(patientId);

        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(true);
        when(userRepository.findById(actorUserId)).thenReturn(Optional.of(author));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> nursingNoteService.createNote(request, Locale.US));
        verifyNoInteractions(nursingNoteRepository);
    }

    @Test
    void appendAddendum_addsEntryAndLogsAudit() {
        UUID actorUserId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID addendumAuthorStaffId = UUID.randomUUID();
        Locale locale = Locale.UK;

        Patient patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Alice");
        patient.setLastName("Smith");

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("City Hospital");

        User author = new User();
        author.setId(actorUserId);
        author.setFirstName("Alex");
        author.setLastName("Nurse");

        UserRoleHospitalAssignment staffAssignment = new UserRoleHospitalAssignment();
        staffAssignment.setId(UUID.randomUUID());
        staffAssignment.setHospital(hospital);

        Staff staff = new Staff();
        staff.setId(addendumAuthorStaffId);
        staff.setAssignment(staffAssignment);
        staff.setJobTitle(JobTitle.NURSE);
        staff.setLicenseNumber("RN-2222");
        staff.setUser(author);
        staff.setHospital(hospital);

        NursingNote existing = new NursingNote();
        existing.setId(noteId);
        existing.setPatient(patient);
        existing.setHospital(hospital);
        existing.setAuthor(author);
        existing.setAuthorStaff(staff);
        existing.setTemplate(NursingNoteTemplate.DAR);
        existing.setEducationEntries(new java.util.ArrayList<>());
        existing.setInterventionEntries(new java.util.ArrayList<>());
        existing.setAddenda(new java.util.ArrayList<>());
        existing.setCreatedAt(LocalDateTime.now().minusHours(2));
        existing.setDocumentedAt(LocalDateTime.now().minusHours(2));
        existing.setUpdatedAt(LocalDateTime.now().minusHours(1));

        NursingNoteResponseDTO mappedResponse = NursingNoteResponseDTO.builder()
            .id(noteId)
            .patientId(patientId)
            .build();

        NursingNoteAddendumRequestDTO request = new NursingNoteAddendumRequestDTO();
        request.setContent("Clarified medication instructions");
        request.setAttestAccuracy(true);
        request.setAttestNoAbbreviations(true);
        request.setEventOccurredAt(OffsetDateTime.now().minusHours(1));
        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(true);

        when(nursingNoteRepository.findByIdAndHospital_Id(noteId, hospitalId)).thenReturn(Optional.of(existing));
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setId(UUID.randomUUID());
        registration.setActive(true);
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(registration));
        when(userRepository.findById(actorUserId)).thenReturn(Optional.of(author));
        when(staffRepository.findByUserIdAndHospitalId(actorUserId, hospitalId)).thenReturn(Optional.of(staff));
        when(nursingNoteRepository.save(any(NursingNote.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(nursingNoteMapper.toResponse(existing)).thenReturn(mappedResponse);

        NursingNoteResponseDTO response = nursingNoteService.appendAddendum(noteId, hospitalId, request, locale);

        assertNotNull(response);
        assertEquals(noteId, response.getId());
        assertEquals(1, existing.getAddenda().size());
        verify(auditEventLogService).logEvent(any(AuditEventRequestDTO.class));
    }

    @Test
    void getRecentNotes_requiresViewPermission() {
        UUID actorUserId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);

        assertThrows(BusinessException.class,
            () -> nursingNoteService.getRecentNotes(patientId, hospitalId, 5, Locale.US));

        verifyNoInteractions(nursingNoteRepository);
    }

    @Test
    void appendAddendum_throwsWhenNoteMissing() {
        UUID actorUserId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        Locale locale = Locale.FRANCE;

        NursingNoteAddendumRequestDTO request = new NursingNoteAddendumRequestDTO();
        request.setContent("Late entry");
        request.setAttestAccuracy(true);
        request.setAttestNoAbbreviations(true);

    when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
    when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(true);
        when(nursingNoteRepository.findByIdAndHospital_Id(noteId, hospitalId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
            () -> nursingNoteService.appendAddendum(noteId, hospitalId, request, locale));
        verifyNoInteractions(nursingNoteMapper);
        verifyNoInteractions(auditEventLogService);
    }

    @Test
    void appendAddendum_throwsWhenAuthorMissing() {
        UUID actorUserId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        NursingNote existing = new NursingNote();
        existing.setId(noteId);
        Patient patient = new Patient();
        patient.setId(patientId);
        existing.setPatient(patient);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        existing.setHospital(hospital);
        existing.setAddenda(new java.util.ArrayList<>());

        NursingNoteAddendumRequestDTO request = new NursingNoteAddendumRequestDTO();
        request.setContent("Missing author path");

        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(true);
        when(nursingNoteRepository.findByIdAndHospital_Id(noteId, hospitalId)).thenReturn(Optional.of(existing));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        when(userRepository.findById(actorUserId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
            () -> nursingNoteService.appendAddendum(noteId, hospitalId, request, Locale.US));
        verify(nursingNoteRepository, never()).save(any(NursingNote.class));
        verifyNoInteractions(auditEventLogService);
    }

    @Test
    void createNote_computesReadabilityAndFiltersEmptyEntries() {
        UUID actorUserId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();

        NursingNoteCreateRequestDTO request = NursingNoteCreateRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .template(NursingNoteTemplate.DAR)
            .narrative("Patient is stable.")
            .actionSummary("Administered medication as ordered.")
            .responseSummary("No adverse reactions observed.")
            .educationSummary("Reviewed discharge planning.")
            .educationEntries(List.of(
                NursingNoteEducationDTO.builder().topic("   ").build(),
                NursingNoteEducationDTO.builder()
                    .topic("Discharge plan")
                    .patientUnderstanding("Acknowledged")
                    .teachingMethod("Discussion")
                    .build()
            ))
            .interventionEntries(List.of(
                NursingNoteInterventionDTO.builder().build(),
                NursingNoteInterventionDTO.builder()
                    .description("Coordinated with pharmacy")
                    .followUpActions("Confirm pickup by family")
                    .build()
            ))
            .signAndComplete(true)
            .attestAccuracy(true)
            .attestNoAbbreviations(true)
            .attestSpellCheck(true)
            .build();

        User author = new User();
        author.setId(actorUserId);
        author.setFirstName("Jamie");
        author.setLastName("Rivers");

        Patient patient = new Patient();
        patient.setId(patientId);

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);

        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(assignmentId);

        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(false);
        when(roleValidator.isDoctor(actorUserId, hospitalId)).thenReturn(true);
        when(roleValidator.getCurrentAssignmentForHospital()).thenReturn(assignment);

        when(userRepository.findById(actorUserId)).thenReturn(Optional.of(author));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findByUserIdAndHospitalId(actorUserId, hospitalId)).thenReturn(Optional.empty());
        when(nursingNoteRepository.save(any(NursingNote.class))).thenAnswer(invocation -> {
            NursingNote note = invocation.getArgument(0);
            note.setId(noteId);
            return note;
        });
        when(nursingNoteMapper.toResponse(any(NursingNote.class))).thenAnswer(invocation -> {
            NursingNote note = invocation.getArgument(0);
            return NursingNoteResponseDTO.builder().id(note.getId()).build();
        });

        NursingNoteResponseDTO response = nursingNoteService.createNote(request, Locale.US);

        assertNotNull(response);
        assertEquals(noteId, response.getId());

        ArgumentCaptor<NursingNote> captor = ArgumentCaptor.forClass(NursingNote.class);
        verify(nursingNoteRepository).save(captor.capture());
        NursingNote saved = captor.getValue();
        assertEquals(1, saved.getEducationEntries().size());
        assertEquals(1, saved.getInterventionEntries().size());
        Double expectedReadability = computeExpectedReadability(
            saved.getNarrative(),
            saved.getActionSummary(),
            saved.getResponseSummary(),
            saved.getEducationSummary()
        );
        assertEquals(expectedReadability, saved.getReadabilityScore());
    assertEquals("Jamie Rivers", saved.getSignedByName());
    assertEquals("Jamie Rivers", saved.getAuthorName());
    assertNull(saved.getSignedByCredentials());
        verify(auditEventLogService).logEvent(any(AuditEventRequestDTO.class));
    }

    @Test
    void createNote_skipsAuditWhenAssignmentMissing() {
        UUID actorUserId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        NursingNoteCreateRequestDTO request = NursingNoteCreateRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .template(NursingNoteTemplate.DAR)
            .narrative("Brief summary")
            .actionSummary("Documented medication administration")
            .responseSummary("Patient tolerated well")
            .educationSummary("Reinforced discharge plan")
            .attestAccuracy(true)
            .attestNoAbbreviations(true)
            .attestSpellCheck(true)
            .build();

        User author = new User();
        author.setId(actorUserId);
        author.setFirstName("Dana");
        author.setLastName("Nguyen");

        Patient patient = new Patient();
        patient.setId(patientId);

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);

        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(false);
        when(roleValidator.isHospitalAdmin(actorUserId, hospitalId)).thenReturn(true);
        when(roleValidator.getCurrentAssignmentForHospital()).thenReturn(null);

        when(userRepository.findById(actorUserId)).thenReturn(Optional.of(author));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findByUserIdAndHospitalId(actorUserId, hospitalId)).thenReturn(Optional.empty());
        when(nursingNoteRepository.save(any(NursingNote.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(nursingNoteMapper.toResponse(any(NursingNote.class))).thenReturn(new NursingNoteResponseDTO());

        nursingNoteService.createNote(request, Locale.US);

        verify(nursingNoteRepository).save(any(NursingNote.class));
        verify(auditEventLogService, never()).logEvent(any(AuditEventRequestDTO.class));
    }

    @Test
    void getRecentNotes_returnsLimitedResults() {
        UUID actorUserId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        NursingNote noteOne = new NursingNote();
        noteOne.setId(UUID.randomUUID());
        NursingNote noteTwo = new NursingNote();
        noteTwo.setId(UUID.randomUUID());
        NursingNote noteThree = new NursingNote();
        noteThree.setId(UUID.randomUUID());
        List<NursingNote> notes = Arrays.asList(noteOne, noteTwo, noteThree);

        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(false);
        when(roleValidator.isDoctor(actorUserId, hospitalId)).thenReturn(true);

        when(nursingNoteRepository.findTop50ByPatient_IdAndHospital_IdOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(notes);
        when(nursingNoteMapper.toResponse(any(NursingNote.class))).thenAnswer(invocation -> {
            NursingNote note = invocation.getArgument(0);
            return NursingNoteResponseDTO.builder().id(note.getId()).build();
        });

        List<NursingNoteResponseDTO> responses = nursingNoteService.getRecentNotes(patientId, hospitalId, 1, Locale.US);

        assertEquals(1, responses.size());
        assertEquals(noteOne.getId(), responses.get(0).getId());
        verify(nursingNoteRepository)
            .findTop50ByPatient_IdAndHospital_IdOrderByCreatedAtDesc(patientId, hospitalId);
    }

    @Test
    void getNote_returnsMappedResponseWhenPresent() {
        UUID actorUserId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        NursingNote note = new NursingNote();
        note.setId(noteId);

        NursingNoteResponseDTO mapped = NursingNoteResponseDTO.builder()
            .id(noteId)
            .build();

        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.getCurrentHospitalId()).thenReturn(hospitalId);
        when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(true);
        when(nursingNoteRepository.findByIdAndHospital_Id(noteId, hospitalId)).thenReturn(Optional.of(note));
        when(nursingNoteMapper.toResponse(note)).thenReturn(mapped);

        NursingNoteResponseDTO response = nursingNoteService.getNote(noteId, null, Locale.CANADA);

        assertNotNull(response);
        assertEquals(noteId, response.getId());
        verify(nursingNoteRepository).findByIdAndHospital_Id(noteId, hospitalId);
        verify(nursingNoteMapper).toResponse(note);
    }

    @Test
    void getNote_missingNoteThrowsResourceNotFound() {
        UUID actorUserId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isNurse(actorUserId, hospitalId)).thenReturn(true);
        when(nursingNoteRepository.findByIdAndHospital_Id(noteId, hospitalId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
            () -> nursingNoteService.getNote(noteId, hospitalId, Locale.US));
        verify(nursingNoteRepository).findByIdAndHospital_Id(noteId, hospitalId);
    }

    @Test
    void getNote_requiresHospitalContextWhenOmitted() {
        UUID actorUserId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();

        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.getCurrentHospitalId()).thenReturn(null);

        assertThrows(BusinessException.class,
            () -> nursingNoteService.getNote(noteId, null, Locale.US));
        verifyNoInteractions(nursingNoteRepository);
    }

    private static Double computeExpectedReadability(String... segments) {
        List<String> filtered = Arrays.stream(segments)
            .filter(str -> str != null && !str.isBlank())
            .toList();
        if (filtered.isEmpty()) {
            return null;
        }
        String combined = String.join(" ", filtered);
        if (combined.isBlank()) {
            return null;
        }
        String[] sentences = combined.split("[.!?]+\\s*");
        int sentenceCount = Math.max(1, sentences.length);
        String[] words = combined.trim().split("\\s+");
        int wordCount = Math.max(1, words.length);
        int syllableCount = Math.max(1, countSyllables(words));
        double wordsPerSentence = (double) wordCount / sentenceCount;
        double syllablesPerWord = (double) syllableCount / wordCount;
        double flesch = 206.835 - (1.015 * wordsPerSentence) - (84.6 * syllablesPerWord);
        double normalized = Math.max(0d, Math.min(100d, flesch));
        return Math.round(normalized * 100.0) / 100.0;
    }

    private static int countSyllables(String[] words) {
        int syllables = 0;
        for (String word : words) {
            syllables += countSyllables(word);
        }
        return Math.max(syllables, words.length);
    }

    private static int countSyllables(String word) {
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
}
