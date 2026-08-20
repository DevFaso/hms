package com.example.hms.service;

import com.example.hms.enums.BedStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Bed;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Ward;
import com.example.hms.payload.dto.bed.BedRequestDTO;
import com.example.hms.payload.dto.bed.BedResponseDTO;
import com.example.hms.payload.dto.bed.BedStatusUpdateRequestDTO;
import com.example.hms.payload.dto.bed.WardRequestDTO;
import com.example.hms.payload.dto.bed.WardResponseDTO;
import com.example.hms.repository.BedRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.WardRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ward/bed CRUD with the house tenant idiom:
 * {@code RoleValidator.requireActiveHospitalId()} (null = super-admin
 * unscoped, honors the X-Hospital-Id pin). Cross-tenant reads surface as
 * {@link ResourceNotFoundException} (404, no existence leak); writes always
 * require a concrete hospital scope.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BedManagementServiceImpl implements BedManagementService {

    private static final String WARD_NOT_FOUND = "Ward not found: ";
    private static final String BED_NOT_FOUND = "Bed not found: ";

    private final WardRepository wardRepository;
    private final BedRepository bedRepository;
    private final DepartmentRepository departmentRepository;
    private final HospitalRepository hospitalRepository;
    private final RoleValidator roleValidator;

    /* ═══════════════════════ Wards ═══════════════════════ */

    @Override
    public List<WardResponseDTO> getWards(boolean includeInactive) {
        UUID scope = roleValidator.requireActiveHospitalId();
        List<Ward> wards = scope == null
            ? wardRepository.findAll()
            : wardRepository.findByHospital_Id(scope);
        Map<UUID, Map<BedStatus, Long>> counts = bedCountsByWard(scope);
        return wards.stream()
            .filter(w -> includeInactive || w.isActive())
            .map(w -> toWardDto(w, counts.getOrDefault(w.getId(), Map.of())))
            .toList();
    }

    @Override
    @Transactional
    public WardResponseDTO createWard(WardRequestDTO request) {
        UUID hospitalId = requireWriteScope();
        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + hospitalId));

        String code = request.getCode().trim();
        if (wardRepository.existsByHospital_IdAndCodeIgnoreCase(hospitalId, code)) {
            throw new BusinessException("A ward with code '" + code + "' already exists in this hospital.");
        }

        Ward ward = Ward.builder()
            .hospital(hospital)
            .name(request.getName().trim())
            .code(code)
            .wardType(request.getWardType())
            .floor(request.getFloor())
            .description(request.getDescription())
            .department(resolveDepartment(request.getDepartmentId(), hospitalId))
            .build();
        if (request.getActive() != null) {
            ward.setActive(request.getActive());
        }
        return toWardDto(wardRepository.save(ward), Map.of());
    }

    @Override
    @Transactional
    public WardResponseDTO updateWard(UUID wardId, WardRequestDTO request) {
        UUID hospitalId = requireWriteScope();
        Ward ward = wardRepository.findByIdAndHospital_Id(wardId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(WARD_NOT_FOUND + wardId));

        String code = request.getCode().trim();
        if (wardRepository.existsByHospital_IdAndCodeIgnoreCaseAndIdNot(hospitalId, code, wardId)) {
            throw new BusinessException("A ward with code '" + code + "' already exists in this hospital.");
        }

        ward.setName(request.getName().trim());
        ward.setCode(code);
        ward.setWardType(request.getWardType());
        ward.setFloor(request.getFloor());
        ward.setDescription(request.getDescription());
        ward.setDepartment(resolveDepartment(request.getDepartmentId(), hospitalId));
        if (request.getActive() != null) {
            ward.setActive(request.getActive());
        }
        Ward saved = wardRepository.save(ward);
        return toWardDto(saved, countStatuses(bedRepository.findByWard_IdAndActiveTrue(saved.getId())));
    }

    @Override
    @Transactional
    public void deleteWard(UUID wardId) {
        UUID hospitalId = requireWriteScope();
        Ward ward = wardRepository.findByIdAndHospital_Id(wardId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(WARD_NOT_FOUND + wardId));
        if (!bedRepository.findByWard_Id(wardId).isEmpty()) {
            throw new BusinessException(
                "Ward '" + ward.getCode() + "' still has beds. Delete its beds first or deactivate the ward.");
        }
        wardRepository.delete(ward);
    }

    /* ═══════════════════════ Beds ═══════════════════════ */

    @Override
    public List<BedResponseDTO> getBedsByWard(UUID wardId) {
        Ward ward = loadWardReadScoped(wardId);
        return bedRepository.findByWard_IdOrderByBedNumberAsc(ward.getId()).stream()
            .map(this::toBedDto)
            .toList();
    }

    @Override
    public List<BedResponseDTO> getAvailableBeds() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException(
                "Hospital context required to list available beds. Super-admins must pin a hospital (X-Hospital-Id).");
        }
        return bedRepository
            .findByWard_Hospital_IdAndStatusAndActiveTrueOrderByBedNumberAsc(hospitalId, BedStatus.AVAILABLE)
            .stream()
            .map(this::toBedDto)
            .toList();
    }

    @Override
    @Transactional
    public BedResponseDTO createBed(UUID wardId, BedRequestDTO request) {
        UUID hospitalId = requireWriteScope();
        Ward ward = wardRepository.findByIdAndHospital_Id(wardId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(WARD_NOT_FOUND + wardId));

        String bedNumber = request.getBedNumber().trim();
        if (bedRepository.existsByWard_IdAndBedNumberIgnoreCase(wardId, bedNumber)) {
            throw new BusinessException(
                "Bed '" + bedNumber + "' already exists in ward '" + ward.getCode() + "'.");
        }

        Bed bed = Bed.builder()
            .ward(ward)
            .bedNumber(bedNumber)
            .bedType(request.getBedType())
            .floor(request.getFloor() != null ? request.getFloor() : ward.getFloor())
            .roomNumber(request.getRoomNumber())
            .notes(request.getNotes())
            .build();
        if (request.getActive() != null) {
            bed.setActive(request.getActive());
        }
        return toBedDto(bedRepository.save(bed));
    }

    @Override
    @Transactional
    public BedResponseDTO updateBed(UUID bedId, BedRequestDTO request) {
        UUID hospitalId = requireWriteScope();
        Bed bed = bedRepository.findByIdAndWard_Hospital_Id(bedId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(BED_NOT_FOUND + bedId));

        String bedNumber = request.getBedNumber().trim();
        if (bedRepository.existsByWard_IdAndBedNumberIgnoreCaseAndIdNot(bed.getWard().getId(), bedNumber, bedId)) {
            throw new BusinessException(
                "Bed '" + bedNumber + "' already exists in ward '" + bed.getWard().getCode() + "'.");
        }
        if (Boolean.FALSE.equals(request.getActive()) && bed.getStatus() == BedStatus.OCCUPIED) {
            throw new BusinessException("An occupied bed cannot be retired. Discharge or reassign the patient first.");
        }

        bed.setBedNumber(bedNumber);
        bed.setBedType(request.getBedType());
        bed.setFloor(request.getFloor());
        bed.setRoomNumber(request.getRoomNumber());
        bed.setNotes(request.getNotes());
        if (request.getActive() != null) {
            bed.setActive(request.getActive());
        }
        return toBedDto(bedRepository.save(bed));
    }

    @Override
    @Transactional
    public BedResponseDTO updateBedStatus(UUID bedId, BedStatusUpdateRequestDTO request) {
        UUID hospitalId = requireWriteScope();
        Bed bed = bedRepository.findByIdAndWard_Hospital_Id(bedId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(BED_NOT_FOUND + bedId));

        if (request.getStatus() == BedStatus.OCCUPIED) {
            throw new BusinessException("OCCUPIED is set by bed assignment, not manually.");
        }
        if (bed.getStatus() == BedStatus.OCCUPIED) {
            throw new BusinessException("Bed is occupied. Discharge or unassign the patient first.");
        }
        bed.setStatus(request.getStatus());
        if (request.getNotes() != null) {
            bed.setNotes(request.getNotes());
        }
        return toBedDto(bedRepository.save(bed));
    }

    @Override
    @Transactional
    public void deleteBed(UUID bedId) {
        UUID hospitalId = requireWriteScope();
        Bed bed = bedRepository.findByIdAndWard_Hospital_Id(bedId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(BED_NOT_FOUND + bedId));
        if (bed.getStatus() == BedStatus.OCCUPIED) {
            throw new BusinessException("An occupied bed cannot be deleted. Discharge or reassign the patient first.");
        }
        bedRepository.delete(bed);
    }

    /* ═══════════════════════ Helpers ═══════════════════════ */

    private UUID requireWriteScope() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException(
                "Hospital context required to manage wards and beds. Super-admins must pin a hospital (X-Hospital-Id).");
        }
        return hospitalId;
    }

    private Ward loadWardReadScoped(UUID wardId) {
        UUID scope = roleValidator.requireActiveHospitalId();
        return (scope == null
            ? wardRepository.findById(wardId)
            : wardRepository.findByIdAndHospital_Id(wardId, scope))
            .orElseThrow(() -> new ResourceNotFoundException(WARD_NOT_FOUND + wardId));
    }

    private Department resolveDepartment(UUID departmentId, UUID hospitalId) {
        if (departmentId == null) {
            return null;
        }
        Department department = departmentRepository.findById(departmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + departmentId));
        // ── Tenant isolation: a department from another hospital reads as not-found. ──
        if (department.getHospital() == null || !hospitalId.equals(department.getHospital().getId())) {
            throw new ResourceNotFoundException("Department not found: " + departmentId);
        }
        return department;
    }

    private Map<UUID, Map<BedStatus, Long>> bedCountsByWard(UUID hospitalScope) {
        List<Object[]> rows = hospitalScope == null
            ? bedRepository.countAllGroupByWardIdAndStatus()
            : bedRepository.countByHospitalGroupByWardIdAndStatus(hospitalScope);
        Map<UUID, Map<BedStatus, Long>> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.computeIfAbsent((UUID) row[0], id -> new EnumMap<>(BedStatus.class))
                .put((BedStatus) row[1], (Long) row[2]);
        }
        return counts;
    }

    private Map<BedStatus, Long> countStatuses(List<Bed> beds) {
        Map<BedStatus, Long> counts = new EnumMap<>(BedStatus.class);
        for (Bed bed : beds) {
            counts.merge(bed.getStatus(), 1L, Long::sum);
        }
        return counts;
    }

    private WardResponseDTO toWardDto(Ward ward, Map<BedStatus, Long> counts) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        Department department = ward.getDepartment();
        return WardResponseDTO.builder()
            .id(ward.getId())
            .hospitalId(ward.getHospital() != null ? ward.getHospital().getId() : null)
            .name(ward.getName())
            .code(ward.getCode())
            .wardType(ward.getWardType() != null ? ward.getWardType().name() : null)
            .floor(ward.getFloor())
            .description(ward.getDescription())
            .departmentId(department != null ? department.getId() : null)
            .departmentName(department != null ? department.getName() : null)
            .active(ward.isActive())
            .totalBeds(total)
            .availableBeds(counts.getOrDefault(BedStatus.AVAILABLE, 0L))
            .occupiedBeds(counts.getOrDefault(BedStatus.OCCUPIED, 0L))
            .build();
    }

    private BedResponseDTO toBedDto(Bed bed) {
        Ward ward = bed.getWard();
        return BedResponseDTO.builder()
            .id(bed.getId())
            .wardId(ward != null ? ward.getId() : null)
            .wardName(ward != null ? ward.getName() : null)
            .wardCode(ward != null ? ward.getCode() : null)
            .bedNumber(bed.getBedNumber())
            .label(BedAssignmentService.bedLabel(bed))
            .status(bed.getStatus() != null ? bed.getStatus().name() : null)
            .bedType(bed.getBedType())
            .floor(bed.getFloor())
            .roomNumber(bed.getRoomNumber())
            .notes(bed.getNotes())
            .active(bed.isActive())
            .build();
    }
}
