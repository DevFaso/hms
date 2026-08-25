package com.example.hms.service;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.BedStatus;
import com.example.hms.enums.IsolationPrecautionType;
import com.example.hms.enums.WardType;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Admission;
import com.example.hms.model.Bed;
import com.example.hms.model.Hospital;
import com.example.hms.model.IsolationPrecaution;
import com.example.hms.model.Patient;
import com.example.hms.model.Ward;
import com.example.hms.payload.dto.bed.BedBoardDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.BedRepository;
import com.example.hms.repository.IsolationPrecautionRepository;
import com.example.hms.service.impl.BedBoardServiceImpl;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The ward board and census (Tier 2 item 31).
 *
 * <p>The sharpest assertions here are about DISAGREEMENT. The census counts
 * inpatients from admissions and beds from bed status, and when the two
 * diverge that is a bed stuck OCCUPIED with nobody in it — an unallocatable
 * bed produced by a half-failed discharge. The board reports the gap instead
 * of quietly trusting one source, because a lost bed stays lost exactly as
 * long as nothing shows it.
 *
 * <p>The other is the ISOLATION MISMATCH: an airborne case in a ward that
 * cannot contain it is the single thing the board exists to make impossible
 * to miss.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BedBoardServiceImplTest {

    @Spy private Clock clock = Clock.systemDefaultZone();
    @Mock private BedRepository bedRepository;
    @Mock private AdmissionRepository admissionRepository;
    @Mock private IsolationPrecautionRepository precautionRepository;
    @Mock private RoleValidator roleValidator;

    @InjectMocks private BedBoardServiceImpl service;

    private UUID hospitalId;
    private Hospital hospital;
    private Ward generalWard;
    private Ward isolationWard;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);

        generalWard = ward("Maternity A", "MATA", WardType.MATERNITY);
        isolationWard = ward("Isolation", "ISO", WardType.ISOLATION);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(bedRepository.findBoardBeds(any())).thenReturn(List.of());
        when(admissionRepository.findBedHoldingAdmissions(any(), any())).thenReturn(List.of());
        when(precautionRepository.findActiveForHospital(any())).thenReturn(List.of());
    }

    private Ward ward(String name, String code, WardType type) {
        Ward w = Ward.builder()
            .hospital(hospital).name(name).code(code).wardType(type).active(true).build();
        w.setId(UUID.randomUUID());
        return w;
    }

    private Bed bed(Ward ward, String number, String room, BedStatus status) {
        Bed b = Bed.builder()
            .ward(ward).bedNumber(number).roomNumber(room).status(status).active(true).build();
        b.setId(UUID.randomUUID());
        return b;
    }

    private Patient patient(String first, String last) {
        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        p.setFirstName(first);
        p.setLastName(last);
        return p;
    }

    private Admission admission(Patient patient, Bed bed) {
        Admission a = new Admission();
        a.setId(UUID.randomUUID());
        a.setHospital(hospital);
        a.setPatient(patient);
        a.setBed(bed);
        a.setStatus(AdmissionStatus.ACTIVE);
        a.setAdmissionDateTime(LocalDateTime.now().minusDays(3));
        return a;
    }

    private IsolationPrecaution precaution(Patient patient, IsolationPrecautionType type) {
        return IsolationPrecaution.builder()
            .hospital(hospital).patient(patient).precautionType(type)
            .reason("clinical").startedAt(LocalDateTime.now().minusDays(1)).build();
    }

    // ── The grid ────────────────────────────────────────────────────────

    @Test
    void theBoardGroupsBedsByWardThenRoom() {
        Bed a1 = bed(generalWard, "1", "101", BedStatus.AVAILABLE);
        Bed a2 = bed(generalWard, "2", "101", BedStatus.AVAILABLE);
        Bed b1 = bed(generalWard, "3", "102", BedStatus.AVAILABLE);
        when(bedRepository.findBoardBeds(hospitalId)).thenReturn(List.of(a1, a2, b1));

        BedBoardDTO board = service.getBoard();

        assertThat(board.getWards()).hasSize(1);
        BedBoardDTO.WardBoardDTO ward = board.getWards().get(0);
        assertThat(ward.getRooms()).hasSize(2);
        assertThat(ward.getRooms().get(0).getRoomNumber()).isEqualTo("101");
        assertThat(ward.getRooms().get(0).getBeds()).hasSize(2);
        assertThat(ward.getRooms().get(1).getBeds()).hasSize(1);
    }

    @Test
    void aBedWithNoRoomNumberStillAppearsOnTheBoard() {
        // Beds exist before anyone numbers the bays. Dropping them would hide
        // real capacity.
        Bed orphan = bed(generalWard, "7", null, BedStatus.AVAILABLE);
        when(bedRepository.findBoardBeds(hospitalId)).thenReturn(List.of(orphan));

        BedBoardDTO board = service.getBoard();

        assertThat(board.getWards().get(0).getRooms()).hasSize(1);
        assertThat(board.getWards().get(0).getRooms().get(0).getRoomNumber()).isNull();
        assertThat(board.getWards().get(0).getRooms().get(0).getBeds()).hasSize(1);
    }

    @Test
    void anOccupiedBedCarriesTheOccupant() {
        Bed occupied = bed(generalWard, "1", "101", BedStatus.OCCUPIED);
        Patient patient = patient("Aminata", "Diallo");
        Admission admission = admission(patient, occupied);
        when(bedRepository.findBoardBeds(hospitalId)).thenReturn(List.of(occupied));
        when(admissionRepository.findBedHoldingAdmissions(any(), any())).thenReturn(List.of(admission));

        BedBoardDTO board = service.getBoard();

        BedBoardDTO.OccupantDTO occupant =
            board.getWards().get(0).getRooms().get(0).getBeds().get(0).getOccupant();
        assertThat(occupant).isNotNull();
        assertThat(occupant.getPatientName()).isEqualTo("Aminata Diallo");
        assertThat(occupant.getLengthOfStayDays()).isEqualTo(3);
    }

    @Test
    void anEmptyBedHasNoOccupant() {
        Bed free = bed(generalWard, "1", "101", BedStatus.AVAILABLE);
        when(bedRepository.findBoardBeds(hospitalId)).thenReturn(List.of(free));

        BedBoardDTO board = service.getBoard();

        assertThat(board.getWards().get(0).getRooms().get(0).getBeds().get(0).getOccupant()).isNull();
    }

    // ── Isolation on the board ──────────────────────────────────────────

    @Test
    void anAirborneCaseInAGeneralWardIsFlaggedAsAMismatch() {
        // The whole reason the board shows precautions at all.
        Bed occupied = bed(generalWard, "1", "101", BedStatus.OCCUPIED);
        Patient patient = patient("Ibrahim", "Toure");
        when(bedRepository.findBoardBeds(hospitalId)).thenReturn(List.of(occupied));
        when(admissionRepository.findBedHoldingAdmissions(any(), any()))
            .thenReturn(List.of(admission(patient, occupied)));
        when(precautionRepository.findActiveForHospital(hospitalId))
            .thenReturn(List.of(precaution(patient, IsolationPrecautionType.AIRBORNE)));

        BedBoardDTO board = service.getBoard();

        BedBoardDTO.OccupantDTO occupant =
            board.getWards().get(0).getRooms().get(0).getBeds().get(0).getOccupant();
        assertThat(occupant.isRequiresIsolationWard()).isTrue();
        assertThat(occupant.isIsolationMismatch()).isTrue();
    }

    @Test
    void theSameCaseInAnIsolationWardIsNotAMismatch() {
        Bed occupied = bed(isolationWard, "1", "201", BedStatus.OCCUPIED);
        Patient patient = patient("Ibrahim", "Toure");
        when(bedRepository.findBoardBeds(hospitalId)).thenReturn(List.of(occupied));
        when(admissionRepository.findBedHoldingAdmissions(any(), any()))
            .thenReturn(List.of(admission(patient, occupied)));
        when(precautionRepository.findActiveForHospital(hospitalId))
            .thenReturn(List.of(precaution(patient, IsolationPrecautionType.AIRBORNE)));

        BedBoardDTO board = service.getBoard();

        BedBoardDTO.OccupantDTO occupant =
            board.getWards().get(0).getRooms().get(0).getBeds().get(0).getOccupant();
        assertThat(occupant.isRequiresIsolationWard()).isTrue();
        assertThat(occupant.isIsolationMismatch()).isFalse();
        assertThat(board.getWards().get(0).isIsolationCapable()).isTrue();
    }

    @Test
    void contactPrecautionsAreShownButAreNotAPlacementMismatch() {
        Bed occupied = bed(generalWard, "1", "101", BedStatus.OCCUPIED);
        Patient patient = patient("Fatou", "Sow");
        when(bedRepository.findBoardBeds(hospitalId)).thenReturn(List.of(occupied));
        when(admissionRepository.findBedHoldingAdmissions(any(), any()))
            .thenReturn(List.of(admission(patient, occupied)));
        when(precautionRepository.findActiveForHospital(hospitalId))
            .thenReturn(List.of(precaution(patient, IsolationPrecautionType.CONTACT)));

        BedBoardDTO board = service.getBoard();

        BedBoardDTO.OccupantDTO occupant =
            board.getWards().get(0).getRooms().get(0).getBeds().get(0).getOccupant();
        assertThat(occupant.getIsolationPrecautions()).containsExactly(IsolationPrecautionType.CONTACT);
        assertThat(occupant.isIsolationMismatch()).isFalse();
    }

    @Test
    void concurrentPrecautionsAreAllListed() {
        // A viral haemorrhagic fever is contact AND droplet — showing one
        // would under-communicate the other.
        Bed occupied = bed(generalWard, "1", "101", BedStatus.OCCUPIED);
        Patient patient = patient("Fatou", "Sow");
        when(bedRepository.findBoardBeds(hospitalId)).thenReturn(List.of(occupied));
        when(admissionRepository.findBedHoldingAdmissions(any(), any()))
            .thenReturn(List.of(admission(patient, occupied)));
        when(precautionRepository.findActiveForHospital(hospitalId)).thenReturn(List.of(
            precaution(patient, IsolationPrecautionType.CONTACT),
            precaution(patient, IsolationPrecautionType.DROPLET)));

        BedBoardDTO board = service.getBoard();

        assertThat(board.getWards().get(0).getRooms().get(0).getBeds().get(0)
            .getOccupant().getIsolationPrecautions())
            .containsExactly(IsolationPrecautionType.CONTACT, IsolationPrecautionType.DROPLET);
    }

    // ── The census ──────────────────────────────────────────────────────

    @Test
    void theCensusCountsBedsByStatusAndInpatientsFromAdmissions() {
        Bed occupied = bed(generalWard, "1", "101", BedStatus.OCCUPIED);
        Bed free = bed(generalWard, "2", "101", BedStatus.AVAILABLE);
        Bed broken = bed(generalWard, "3", "101", BedStatus.MAINTENANCE);
        Patient patient = patient("Aminata", "Diallo");
        when(bedRepository.findBoardBeds(hospitalId)).thenReturn(List.of(occupied, free, broken));
        when(admissionRepository.findBedHoldingAdmissions(any(), any()))
            .thenReturn(List.of(admission(patient, occupied)));

        BedBoardDTO.CensusDTO census = service.getBoard().getCensus();

        assertThat(census.getTotalBeds()).isEqualTo(3);
        assertThat(census.getOccupiedBeds()).isEqualTo(1);
        assertThat(census.getAvailableBeds()).isEqualTo(1);
        assertThat(census.getOutOfServiceBeds()).isEqualTo(1);
        assertThat(census.getInpatientCount()).isEqualTo(1);
        assertThat(census.getOrphanedOccupiedBeds()).isZero();
        assertThat(census.getOccupancyRate()).isEqualByComparingTo(new BigDecimal("33.3"));
    }

    @Test
    void aBedLeftOccupiedWithNobodyInItIsReportedNotHidden() {
        // A half-failed discharge leaves a bed OCCUPIED that no admission
        // points at. Nobody can allocate it, and it stays lost for exactly as
        // long as nothing surfaces it.
        Bed stuck = bed(generalWard, "1", "101", BedStatus.OCCUPIED);
        when(bedRepository.findBoardBeds(hospitalId)).thenReturn(List.of(stuck));
        when(admissionRepository.findBedHoldingAdmissions(any(), any())).thenReturn(List.of());

        BedBoardDTO.CensusDTO census = service.getBoard().getCensus();

        assertThat(census.getOccupiedBeds()).isEqualTo(1);
        assertThat(census.getInpatientCount()).isZero();
        assertThat(census.getOrphanedOccupiedBeds()).isEqualTo(1);
    }

    @Test
    void theCensusCountsExpectedDischargesToday() {
        Bed today = bed(generalWard, "1", "101", BedStatus.OCCUPIED);
        Bed later = bed(generalWard, "2", "101", BedStatus.OCCUPIED);
        Admission goingHome = admission(patient("A", "One"), today);
        goingHome.setExpectedDischargeDateTime(LocalDateTime.now().withHour(16));
        Admission staying = admission(patient("B", "Two"), later);
        staying.setExpectedDischargeDateTime(LocalDateTime.now().plusDays(4));
        when(bedRepository.findBoardBeds(hospitalId)).thenReturn(List.of(today, later));
        when(admissionRepository.findBedHoldingAdmissions(any(), any()))
            .thenReturn(List.of(goingHome, staying));

        assertThat(service.getBoard().getCensus().getExpectedDischargesToday()).isEqualTo(1);
    }

    @Test
    void theCensusCountsPatientsOnIsolationOnceEachRegardlessOfPrecautionCount() {
        Bed occupied = bed(generalWard, "1", "101", BedStatus.OCCUPIED);
        Patient patient = patient("Fatou", "Sow");
        when(bedRepository.findBoardBeds(hospitalId)).thenReturn(List.of(occupied));
        when(admissionRepository.findBedHoldingAdmissions(any(), any()))
            .thenReturn(List.of(admission(patient, occupied)));
        when(precautionRepository.findActiveForHospital(hospitalId)).thenReturn(List.of(
            precaution(patient, IsolationPrecautionType.CONTACT),
            precaution(patient, IsolationPrecautionType.DROPLET)));

        assertThat(service.getBoard().getCensus().getPatientsOnIsolation()).isEqualTo(1);
    }

    @Test
    void anEmptyHospitalReportsZeroRatherThanDividingByZero() {
        BedBoardDTO board = service.getBoard();

        assertThat(board.getWards()).isEmpty();
        assertThat(board.getCensus().getTotalBeds()).isZero();
        assertThat(board.getCensus().getOccupancyRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Tenancy ─────────────────────────────────────────────────────────

    @Test
    void aSuperAdminWithNoActiveHospitalHasNoBoardToRead() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);

        assertThatThrownBy(() -> service.getBoard())
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital is required");
    }
}
