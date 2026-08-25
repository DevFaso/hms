package com.example.hms.service.impl;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.BedStatus;
import com.example.hms.enums.IsolationPrecautionType;
import com.example.hms.enums.WardType;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Admission;
import com.example.hms.model.Bed;
import com.example.hms.model.IsolationPrecaution;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.Ward;
import com.example.hms.payload.dto.bed.BedBoardDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.BedRepository;
import com.example.hms.repository.IsolationPrecautionRepository;
import com.example.hms.service.BedBoardService;
import com.example.hms.utility.ElapsedTime;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The ward board and the census (Tier 2 item 31).
 *
 * <p><b>Three queries, not one per bed.</b> The board is assembled from all
 * active beds, all bed-holding admissions, and all active isolation
 * precautions for the hospital, joined in memory. Resolving occupants or
 * precautions lazily would be one query per bed, which on a sixty-bed ward is
 * the difference between a board and a spinner.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BedBoardServiceImpl implements BedBoardService {

    /** Admission states that hold a bed — the same set BedAssignmentService uses. */
    private static final Set<AdmissionStatus> BED_HOLDING_STATUSES = Set.of(
        AdmissionStatus.PENDING, AdmissionStatus.ACTIVE,
        AdmissionStatus.ON_LEAVE, AdmissionStatus.AWAITING_DISCHARGE);

    /** Ward types that can hold an airborne case. */
    private static final Set<WardType> ISOLATION_CAPABLE = Set.of(WardType.ISOLATION);

    private final Clock clock;
    private final BedRepository bedRepository;
    private final AdmissionRepository admissionRepository;
    private final IsolationPrecautionRepository precautionRepository;
    private final RoleValidator roleValidator;

    @Override
    public BedBoardDTO getBoard() {
        UUID hospitalId = requireHospital();

        List<Bed> beds = bedRepository.findBoardBeds(hospitalId);
        List<Admission> admissions =
            admissionRepository.findBedHoldingAdmissions(hospitalId, BED_HOLDING_STATUSES);
        Map<UUID, List<IsolationPrecaution>> precautionsByPatient =
            groupPrecautionsByPatient(precautionRepository.findActiveForHospital(hospitalId));

        Map<UUID, Admission> admissionByBedId = new HashMap<>();
        for (Admission admission : admissions) {
            if (admission.getBed() != null) {
                admissionByBedId.put(admission.getBed().getId(), admission);
            }
        }

        List<BedBoardDTO.WardBoardDTO> wards =
            buildWards(beds, admissionByBedId, precautionsByPatient, hospitalId);

        return BedBoardDTO.builder()
            .hospitalId(hospitalId)
            .generatedAt(LocalDateTime.now(clock))
            .census(buildCensus(beds, admissions, precautionsByPatient))
            .wards(wards)
            .build();
    }

    // ── Assembly ────────────────────────────────────────────────────────

    private Map<UUID, List<IsolationPrecaution>> groupPrecautionsByPatient(
        List<IsolationPrecaution> precautions) {
        Map<UUID, List<IsolationPrecaution>> byPatient = new HashMap<>();
        for (IsolationPrecaution precaution : precautions) {
            if (precaution.getPatient() == null) {
                continue;
            }
            byPatient.computeIfAbsent(precaution.getPatient().getId(), k -> new ArrayList<>())
                .add(precaution);
        }
        return byPatient;
    }

    private List<BedBoardDTO.WardBoardDTO> buildWards(
        List<Bed> beds,
        Map<UUID, Admission> admissionByBedId,
        Map<UUID, List<IsolationPrecaution>> precautionsByPatient,
        UUID hospitalId) {

        Map<UUID, List<Bed>> bedsByWard = new LinkedHashMap<>();
        for (Bed bed : beds) {
            bedsByWard.computeIfAbsent(bed.getWard().getId(), k -> new ArrayList<>()).add(bed);
        }

        List<BedBoardDTO.WardBoardDTO> result = new ArrayList<>();
        for (List<Bed> wardBeds : bedsByWard.values()) {
            Ward ward = wardBeds.get(0).getWard();
            boolean isolationCapable = ISOLATION_CAPABLE.contains(ward.getWardType());

            long occupied = wardBeds.stream().filter(b -> b.getStatus() == BedStatus.OCCUPIED).count();
            long available = wardBeds.stream().filter(b -> b.getStatus() == BedStatus.AVAILABLE).count();

            result.add(BedBoardDTO.WardBoardDTO.builder()
                .wardId(ward.getId())
                .wardName(ward.getName())
                .wardCode(ward.getCode())
                .wardType(ward.getWardType())
                .floor(ward.getFloor())
                .totalBeds(wardBeds.size())
                .occupiedBeds(occupied)
                .availableBeds(available)
                .occupancyRate(rate(occupied, wardBeds.size()))
                .isolationCapable(isolationCapable)
                .rooms(buildRooms(wardBeds, admissionByBedId, precautionsByPatient,
                    isolationCapable, hospitalId))
                .build());
        }
        result.sort(Comparator.comparing(BedBoardDTO.WardBoardDTO::getWardName,
            Comparator.nullsLast(String::compareToIgnoreCase)));
        return result;
    }

    private List<BedBoardDTO.RoomBoardDTO> buildRooms(
        List<Bed> wardBeds,
        Map<UUID, Admission> admissionByBedId,
        Map<UUID, List<IsolationPrecaution>> precautionsByPatient,
        boolean isolationCapable,
        UUID hospitalId) {

        // A null room number is a real state — beds exist before anyone gets
        // round to numbering the bays — so it becomes its own group rather
        // than dropping those beds off the board entirely.
        Map<String, List<Bed>> bedsByRoom = new LinkedHashMap<>();
        for (Bed bed : wardBeds) {
            bedsByRoom.computeIfAbsent(bed.getRoomNumber(), k -> new ArrayList<>()).add(bed);
        }

        List<BedBoardDTO.RoomBoardDTO> rooms = new ArrayList<>();
        for (Map.Entry<String, List<Bed>> entry : bedsByRoom.entrySet()) {
            List<BedBoardDTO.BedBoardEntryDTO> entries = entry.getValue().stream()
                .sorted(Comparator.comparing(Bed::getBedNumber,
                    Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(bed -> toEntry(bed, admissionByBedId.get(bed.getId()),
                    precautionsByPatient, isolationCapable, hospitalId))
                .toList();
            rooms.add(BedBoardDTO.RoomBoardDTO.builder()
                .roomNumber(entry.getKey())
                .beds(entries)
                .build());
        }
        rooms.sort(Comparator.comparing(BedBoardDTO.RoomBoardDTO::getRoomNumber,
            Comparator.nullsLast(String::compareToIgnoreCase)));
        return rooms;
    }

    private BedBoardDTO.BedBoardEntryDTO toEntry(
        Bed bed,
        Admission admission,
        Map<UUID, List<IsolationPrecaution>> precautionsByPatient,
        boolean isolationCapable,
        UUID hospitalId) {

        return BedBoardDTO.BedBoardEntryDTO.builder()
            .bedId(bed.getId())
            .bedNumber(bed.getBedNumber())
            .bedType(bed.getBedType())
            .status(bed.getStatus())
            .notes(bed.getNotes())
            .occupant(admission == null ? null
                : toOccupant(admission, precautionsByPatient, isolationCapable, hospitalId))
            .build();
    }

    private BedBoardDTO.OccupantDTO toOccupant(
        Admission admission,
        Map<UUID, List<IsolationPrecaution>> precautionsByPatient,
        boolean isolationCapable,
        UUID hospitalId) {

        Patient patient = admission.getPatient();
        UUID patientId = patient != null ? patient.getId() : null;

        List<IsolationPrecaution> precautions =
            precautionsByPatient.getOrDefault(patientId, List.of());
        List<IsolationPrecautionType> types = precautions.stream()
            .map(IsolationPrecaution::getPrecautionType)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
        boolean needsIsolationWard = precautions.stream()
            .anyMatch(IsolationPrecaution::requiresIsolationWard);

        return BedBoardDTO.OccupantDTO.builder()
            .admissionId(admission.getId())
            .patientId(patientId)
            .patientName(patient != null ? patient.getFullName() : null)
            // MRN is per-hospital, so it resolves against the board's hospital
            // rather than being read off the patient as one global identifier.
            .mrn(patient != null ? patient.getMrnForHospital(hospitalId) : null)
            .admittedAt(admission.getAdmissionDateTime())
            .expectedDischargeAt(admission.getExpectedDischargeDateTime())
            .lengthOfStayDays(daysResident(admission))
            .attendingPhysicianName(staffName(admission.getAttendingPhysician()))
            .primaryDiagnosis(admission.getPrimaryDiagnosisDescription())
            .isolationPrecautions(types)
            .requiresIsolationWard(needsIsolationWard)
            .isolationMismatch(needsIsolationWard && !isolationCapable)
            .build();
    }

    // ── Census ──────────────────────────────────────────────────────────

    private BedBoardDTO.CensusDTO buildCensus(
        List<Bed> beds,
        List<Admission> admissions,
        Map<UUID, List<IsolationPrecaution>> precautionsByPatient) {

        Map<BedStatus, Long> counts = new EnumMap<>(BedStatus.class);
        for (Bed bed : beds) {
            counts.merge(bed.getStatus(), 1L, Long::sum);
        }
        long occupied = counts.getOrDefault(BedStatus.OCCUPIED, 0L);
        long available = counts.getOrDefault(BedStatus.AVAILABLE, 0L);
        long reserved = counts.getOrDefault(BedStatus.RESERVED, 0L);
        long outOfService = counts.getOrDefault(BedStatus.MAINTENANCE, 0L)
            + counts.getOrDefault(BedStatus.OUT_OF_SERVICE, 0L);
        long total = beds.size();

        // Counted from admissions, not from bed status. Where the two disagree
        // a bed is stuck OCCUPIED with nobody in it — usually a discharge that
        // half-failed — and that bed is unallocatable until someone notices.
        long inpatients = admissions.size();

        LocalDate today = LocalDate.now(clock);
        long dischargesToday = admissions.stream()
            .map(Admission::getExpectedDischargeDateTime)
            .filter(java.util.Objects::nonNull)
            .filter(d -> d.toLocalDate().equals(today))
            .count();

        long onIsolation = admissions.stream()
            .map(Admission::getPatient)
            .filter(java.util.Objects::nonNull)
            .map(Patient::getId)
            .distinct()
            .filter(precautionsByPatient::containsKey)
            .count();

        return BedBoardDTO.CensusDTO.builder()
            .totalBeds(total)
            .occupiedBeds(occupied)
            .availableBeds(available)
            .reservedBeds(reserved)
            .outOfServiceBeds(outOfService)
            .occupancyRate(rate(occupied, total))
            .inpatientCount(inpatients)
            .orphanedOccupiedBeds(Math.max(0, occupied - inpatients))
            .expectedDischargesToday(dischargesToday)
            .patientsOnIsolation(onIsolation)
            .build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private BigDecimal rate(long occupied, long total) {
        if (total <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(occupied)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    /**
     * Days resident so far — the board wants the current stay, not the final
     * length.
     *
     * <p>Through {@link ElapsedTime} rather than a bare
     * {@code Duration.between}: two {@code LocalDateTime}s carry no offset, so
     * across a daylight-saving transition the wall-clock difference is an hour
     * short of the elapsed time and a three-day stay reports as two.
     */
    private Integer daysResident(Admission admission) {
        LocalDateTime admitted = admission.getAdmissionDateTime();
        if (admitted == null) {
            return null;
        }
        return (int) ElapsedTime.daysBetween(admitted, LocalDateTime.now(clock));
    }

    private String staffName(Staff staff) {
        if (staff == null) {
            return null;
        }
        String full = staff.getFullName();
        return full != null && !full.isBlank() ? full : staff.getName();
    }

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException(
                "An active hospital is required: a bed board belongs to a building.");
        }
        return hospitalId;
    }
}
