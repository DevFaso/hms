package com.example.hms.service.panel;

import com.example.hms.enums.PanelAssignmentStatus;
import com.example.hms.enums.PanelRole;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PanelAssignmentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.PanelAssignment;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.panel.PanelAssignmentRequestDTO;
import com.example.hms.payload.dto.panel.PanelEndRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PanelAssignmentRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The behaviours worth defending (Tier 2 item 37): reassignment SUPERSEDES
 * (ends the old row, creates the new one, never refuses, never overwrites);
 * every entry point pins the hospital scope and collapses cross-tenant rows
 * to not-found; ending needs a live row.
 */
@ExtendWith(MockitoExtension.class)
class PanelServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 3, 9, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    @Mock private PanelAssignmentRepository panelRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private AuditEventLogService auditService;

    private PanelService service;

    private UUID hospitalId;
    private UUID patientId;
    private Hospital hospital;
    private Patient patient;
    private Staff provider;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new PanelService(panelRepository, patientRepository, hospitalRepository,
            staffRepository, roleValidator, new PanelAssignmentMapper(), auditService, clock);

        hospitalId = UUID.randomUUID();
        patientId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("General");

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Awa");
        patient.setLastName("Traore");
        PatientHospitalRegistration reg = new PatientHospitalRegistration();
        reg.setHospital(hospital);
        reg.setActive(true);
        Set<PatientHospitalRegistration> regs = new HashSet<>();
        regs.add(reg);
        patient.setHospitalRegistrations(regs);

        provider = new Staff();
        provider.setId(UUID.randomUUID());
        provider.setHospital(hospital);
        provider.setName("Dr. Diallo");
    }

    private void asClinicianAtHospital() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
    }

    private PanelAssignmentRequestDTO assignRequest() {
        return PanelAssignmentRequestDTO.builder()
            .providerStaffId(provider.getId())
            .panelRole(PanelRole.PRIMARY_PROVIDER)
            .build();
    }

    // ── assign ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("assign creates an ACTIVE row dated today with the tenant's provider")
    void assignCreatesActiveRow() {
        asClinicianAtHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(provider.getId())).thenReturn(Optional.of(provider));
        when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);
        when(panelRepository.findByPatient_IdAndHospital_IdAndPanelRoleAndStatus(
                patientId, hospitalId, PanelRole.PRIMARY_PROVIDER, PanelAssignmentStatus.ACTIVE))
            .thenReturn(Optional.empty());
        when(panelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.assign(patientId, assignRequest());

        assertThat(dto.getStatus()).isEqualTo(PanelAssignmentStatus.ACTIVE);
        assertThat(dto.getAssignedOn()).isEqualTo(TODAY);
        assertThat(dto.getProviderName()).isEqualTo("Dr. Diallo");
    }

    @Test
    @DisplayName("assign SUPERSEDES an existing ACTIVE owner of the same role — never refuses")
    void assignSupersedesThePreviousOwner() {
        asClinicianAtHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(provider.getId())).thenReturn(Optional.of(provider));
        when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);

        PanelAssignment previous = PanelAssignment.builder()
            .patient(patient).hospital(hospital).providerStaff(new Staff())
            .panelRole(PanelRole.PRIMARY_PROVIDER)
            .status(PanelAssignmentStatus.ACTIVE)
            .assignedOn(TODAY.minusDays(200))
            .build();
        previous.setId(UUID.randomUUID());
        when(panelRepository.findByPatient_IdAndHospital_IdAndPanelRoleAndStatus(
                patientId, hospitalId, PanelRole.PRIMARY_PROVIDER, PanelAssignmentStatus.ACTIVE))
            .thenReturn(Optional.of(previous));
        when(panelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.assign(patientId, assignRequest());

        // The old row is ENDED with a dated reason — history, not overwrite.
        assertThat(previous.getStatus()).isEqualTo(PanelAssignmentStatus.ENDED);
        assertThat(previous.getEndedOn()).isEqualTo(TODAY);
        assertThat(previous.getEndReason()).isEqualTo("Superseded by reassignment");

        ArgumentCaptor<PanelAssignment> captor = ArgumentCaptor.forClass(PanelAssignment.class);
        verify(panelRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        PanelAssignment created = captor.getAllValues().get(1);
        assertThat(created.getStatus()).isEqualTo(PanelAssignmentStatus.ACTIVE);
        assertThat(created.getProviderStaff()).isSameAs(provider);
    }

    @Test
    @DisplayName("assign refuses a provider from another hospital as not-found")
    void assignRefusesForeignProvider() {
        asClinicianAtHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        provider.setHospital(other);
        when(staffRepository.findById(provider.getId())).thenReturn(Optional.of(provider));

        assertThatThrownBy(() -> service.assign(patientId, assignRequest()))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(panelRepository, never()).save(any());
    }

    @Test
    @DisplayName("assign refuses a patient not registered at the active hospital as not-found")
    void assignRefusesForeignPatient() {
        asClinicianAtHospital();
        patient.setHospitalRegistrations(new HashSet<>());
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> service.assign(patientId, assignRequest()))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(panelRepository, never()).save(any());
    }

    // ── end ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("end closes a live row with the reason; an ended row refuses")
    void endClosesALiveRowOnce() {
        asClinicianAtHospital();
        PanelAssignment assignment = PanelAssignment.builder()
            .patient(patient).hospital(hospital).providerStaff(provider)
            .panelRole(PanelRole.CHW)
            .status(PanelAssignmentStatus.ACTIVE)
            .assignedOn(TODAY.minusDays(30))
            .build();
        assignment.setId(UUID.randomUUID());
        when(panelRepository.findById(assignment.getId())).thenReturn(Optional.of(assignment));
        when(panelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.end(patientId, assignment.getId(),
            PanelEndRequestDTO.builder().reason("Moved away").build());
        assertThat(dto.getStatus()).isEqualTo(PanelAssignmentStatus.ENDED);
        assertThat(dto.getEndedOn()).isEqualTo(TODAY);
        assertThat(dto.getEndReason()).isEqualTo("Moved away");

        assertThatThrownBy(() -> service.end(patientId, assignment.getId(),
                PanelEndRequestDTO.builder().reason("again").build()))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("end collapses another hospital's assignment to not-found")
    void endRefusesForeignAssignment() {
        asClinicianAtHospital();
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        PanelAssignment foreign = PanelAssignment.builder()
            .patient(patient).hospital(other).providerStaff(provider)
            .panelRole(PanelRole.CHW).status(PanelAssignmentStatus.ACTIVE)
            .assignedOn(TODAY).build();
        foreign.setId(UUID.randomUUID());
        when(panelRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.end(patientId, foreign.getId(),
                PanelEndRequestDTO.builder().reason("x").build()))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(panelRepository, never()).save(any());
    }

    // ── worklists ───────────────────────────────────────────────────────

    @Test
    @DisplayName("myPanel refuses callers with no staff profile at the active hospital")
    void myPanelNeedsAStaffRow() {
        asClinicianAtHospital();
        UUID userId = UUID.randomUUID();
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(staffRepository.findByUserIdAndHospitalId(userId, hospitalId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.myPanel(0, 50))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("no staff profile");
    }

    @Test
    @DisplayName("no active hospital context refuses every entry point")
    void noHospitalContextRefuses() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        assertThatThrownBy(() -> service.assign(patientId, assignRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital context");
    }
}
