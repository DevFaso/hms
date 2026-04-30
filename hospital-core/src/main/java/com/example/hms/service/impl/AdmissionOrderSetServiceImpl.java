package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AdmissionOrderSetMapper;
import com.example.hms.model.Admission;
import com.example.hms.model.AdmissionOrderSet;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.AdmissionOrderSetRequestDTO;
import com.example.hms.payload.dto.AdmissionOrderSetResponseDTO;
import com.example.hms.payload.dto.orderset.ApplyOrderSetRequestDTO;
import com.example.hms.payload.dto.orderset.AppliedOrderSetSummaryDTO;
import com.example.hms.repository.AdmissionOrderSetRepository;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.AdmissionOrderSetService;
import com.example.hms.service.orderset.DispatchResult;
import com.example.hms.service.orderset.OrderSetApplicationContext;
import com.example.hms.service.orderset.OrderSetItemDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AdmissionOrderSetServiceImpl implements AdmissionOrderSetService {

    private static final Logger logger = LoggerFactory.getLogger(AdmissionOrderSetServiceImpl.class);

    private static final String ORDER_SET_NOT_FOUND = "Admission order set not found";
    private static final String HOSPITAL_NOT_FOUND = "Hospital not found";
    private static final String DEPARTMENT_NOT_FOUND = "Department not found";
    private static final String STAFF_NOT_FOUND = "Staff not found";
    private static final String ADMISSION_NOT_FOUND = "Admission not found";

    private final AdmissionOrderSetRepository orderSetRepository;
    private final AdmissionRepository admissionRepository;
    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;
    private final StaffRepository staffRepository;
    private final AdmissionOrderSetMapper mapper;
    private final OrderSetItemDispatcher dispatcher;

    public AdmissionOrderSetServiceImpl(
        AdmissionOrderSetRepository orderSetRepository,
        AdmissionRepository admissionRepository,
        HospitalRepository hospitalRepository,
        DepartmentRepository departmentRepository,
        StaffRepository staffRepository,
        AdmissionOrderSetMapper mapper,
        OrderSetItemDispatcher dispatcher
    ) {
        this.orderSetRepository = orderSetRepository;
        this.admissionRepository = admissionRepository;
        this.hospitalRepository = hospitalRepository;
        this.departmentRepository = departmentRepository;
        this.staffRepository = staffRepository;
        this.mapper = mapper;
        this.dispatcher = dispatcher;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdmissionOrderSetResponseDTO> list(UUID hospitalId, String search, Pageable pageable) {
        List<AdmissionOrderSet> rows = (search == null || search.isBlank())
            ? orderSetRepository.findByHospitalIdAndActiveOrderByNameAsc(hospitalId, true)
            : orderSetRepository.searchByName(hospitalId, search.trim());
        // The repo doesn't expose a Page-returning variant; slice in-memory to keep the
        // controller contract consistent. Hospital-scope already bounds the result set.
        int from = (int) Math.min(pageable.getOffset(), rows.size());
        int to = (int) Math.min(from + (long) pageable.getPageSize(), rows.size());
        List<AdmissionOrderSetResponseDTO> page = rows.subList(from, to).stream()
            .map(mapper::toDto)
            .toList();
        return new PageImpl<>(page, pageable, rows.size());
    }

    @Override
    @Transactional(readOnly = true)
    public AdmissionOrderSetResponseDTO getById(UUID id) {
        return mapper.toDto(loadOrderSet(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdmissionOrderSetResponseDTO> getVersionHistory(UUID id) {
        return orderSetRepository.findVersionChain(id).stream()
            .map(mapper::toDto)
            .toList();
    }

    @Override
    @Transactional
    public AdmissionOrderSetResponseDTO create(AdmissionOrderSetRequestDTO request) {
        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND));
        Department department = request.getDepartmentId() == null ? null
            : departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException(DEPARTMENT_NOT_FOUND));
        Staff createdBy = staffRepository.findById(request.getCreatedByStaffId())
            .orElseThrow(() -> new ResourceNotFoundException(STAFF_NOT_FOUND));

        AdmissionOrderSet entity = new AdmissionOrderSet();
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setAdmissionType(request.getAdmissionType());
        entity.setDepartment(department);
        entity.setHospital(hospital);
        entity.setOrderItems(request.getOrderItems() == null ? new ArrayList<>() : request.getOrderItems());
        entity.setClinicalGuidelines(request.getClinicalGuidelines());
        entity.setActive(request.getActive() == null || request.getActive());
        entity.setVersion(1);
        entity.setCreatedBy(createdBy);
        entity.setLastModifiedBy(createdBy);
        return mapper.toDto(orderSetRepository.save(entity));
    }

    @Override
    @Transactional
    public AdmissionOrderSetResponseDTO update(UUID id, AdmissionOrderSetRequestDTO request) {
        AdmissionOrderSet parent = loadOrderSet(id);
        // Reject cross-hospital edits: AdmissionOrderSetRequestDTO.hospitalId
        // is @NotNull, so consumers send it on every PUT. Silently reusing
        // parent.hospital (as the original code did) would let a misrouted
        // request mutate another hospital's template; better to fail loudly.
        UUID parentHospitalId = parent.getHospital() != null ? parent.getHospital().getId() : null;
        if (request.getHospitalId() != null && parentHospitalId != null
            && !request.getHospitalId().equals(parentHospitalId)) {
            throw new IllegalArgumentException(
                "Order set " + id + " belongs to hospital " + parentHospitalId
                    + " but request targets " + request.getHospitalId());
        }
        Staff modifiedBy = staffRepository.findById(request.getCreatedByStaffId())
            .orElseThrow(() -> new ResourceNotFoundException(STAFF_NOT_FOUND));
        Department department = request.getDepartmentId() == null ? null
            : departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException(DEPARTMENT_NOT_FOUND));

        // Freeze the parent: keep the row but flip active off so the picker
        // only surfaces the head of the version chain.
        parent.setActive(false);
        parent.setLastModifiedBy(modifiedBy);
        orderSetRepository.save(parent);

        AdmissionOrderSet next = new AdmissionOrderSet();
        next.setName(request.getName());
        next.setDescription(request.getDescription());
        next.setAdmissionType(request.getAdmissionType());
        next.setDepartment(department);
        next.setHospital(parent.getHospital());
        next.setOrderItems(request.getOrderItems() == null ? new ArrayList<>() : request.getOrderItems());
        next.setClinicalGuidelines(request.getClinicalGuidelines());
        next.setActive(true);
        next.setVersion(parent.getVersion() + 1);
        next.setParentOrderSet(parent);
        next.setCreatedBy(parent.getCreatedBy());
        next.setLastModifiedBy(modifiedBy);
        return mapper.toDto(orderSetRepository.save(next));
    }

    @Override
    @Transactional
    public AdmissionOrderSetResponseDTO deactivate(UUID id, String reason, UUID actingStaffId) {
        AdmissionOrderSet entity = loadOrderSet(id);
        Staff actor = staffRepository.findById(actingStaffId)
            .orElseThrow(() -> new ResourceNotFoundException(STAFF_NOT_FOUND));
        entity.deactivate(reason, actor);
        return mapper.toDto(orderSetRepository.save(entity));
    }

    @Override
    @Transactional
    public AppliedOrderSetSummaryDTO applyToAdmission(
        UUID admissionId,
        UUID orderSetId,
        ApplyOrderSetRequestDTO request,
        Locale locale
    ) {
        AdmissionOrderSet orderSet = loadOrderSet(orderSetId);
        if (Boolean.FALSE.equals(orderSet.getActive())) {
            throw new IllegalStateException("Order set " + orderSetId + " is deactivated and cannot be applied");
        }

        Admission admission = admissionRepository.findById(admissionId)
            .orElseThrow(() -> new ResourceNotFoundException(ADMISSION_NOT_FOUND));

        // Resolve the ordering staff's active hospital assignment so the lab
        // fan-out can populate LabOrderRequestDTO.assignmentId (@NotNull).
        // Null is acceptable — the dispatcher returns a skipped result for
        // LAB items in that case rather than 400-ing the whole bundle.
        UUID assignmentId = resolveOrderingAssignmentId(
            request.orderingStaffId(),
            admission.getHospital() != null ? admission.getHospital().getId() : null
        );

        OrderSetApplicationContext ctx = new OrderSetApplicationContext(
            orderSet.getId(),
            orderSet.getName(),
            orderSet.getDescription(),
            admission.getId(),
            admission.getPatient() != null ? admission.getPatient().getId() : null,
            admission.getHospital() != null ? admission.getHospital().getId() : null,
            request.encounterId(),
            request.orderingStaffId(),
            assignmentId,
            admission.getPrimaryDiagnosisCode(),
            request.forceOverride()
        );

        List<UUID> prescriptionIds = new ArrayList<>();
        List<UUID> labOrderIds = new ArrayList<>();
        List<UUID> imagingOrderIds = new ArrayList<>();
        var advisories = new ArrayList<com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard>();
        int skipped = 0;

        List<Map<String, Object>> items = orderSet.getOrderItems() == null
            ? List.of() : orderSet.getOrderItems();
        for (Map<String, Object> item : items) {
            // CdsCriticalBlockException propagates and rolls back the whole
            // transaction; non-blocking advisories are accumulated.
            DispatchResult result = dispatcher.dispatch(item, ctx, locale);
            switch (result.type()) {
                case MEDICATION -> prescriptionIds.add(result.createdId());
                case LAB        -> labOrderIds.add(result.createdId());
                case IMAGING    -> imagingOrderIds.add(result.createdId());
                case SKIPPED    -> { skipped++;
                    logger.debug("orderset {} skipped item: {}", orderSet.getId(), result.skipReason());
                }
            }
            if (!result.cdsAdvisories().isEmpty()) advisories.addAll(result.cdsAdvisories());
        }

        admission.applyOrderSet(orderSet);
        admissionRepository.save(admission);

        return new AppliedOrderSetSummaryDTO(
            orderSet.getId(),
            orderSet.getName(),
            orderSet.getVersion(),
            admission.getId(),
            request.encounterId(),
            List.copyOf(prescriptionIds),
            List.copyOf(labOrderIds),
            List.copyOf(imagingOrderIds),
            skipped,
            List.copyOf(advisories)
        );
    }

    private AdmissionOrderSet loadOrderSet(UUID id) {
        return orderSetRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(ORDER_SET_NOT_FOUND));
    }

    /**
     * Look up the ordering staff's active hospital assignment for the
     * apply target. Returns null when the staff entity has no resolvable
     * user / no active assignment at the hospital — the dispatcher then
     * skips LAB items cleanly rather than NPE-ing inside LabOrderService.
     */
    private UUID resolveOrderingAssignmentId(UUID orderingStaffId, UUID hospitalId) {
        if (orderingStaffId == null || hospitalId == null) return null;
        return staffRepository.findById(orderingStaffId)
            .map(Staff::getAssignment)
            .filter(java.util.Objects::nonNull)
            .filter(a -> a.getHospital() != null && hospitalId.equals(a.getHospital().getId()))
            .map(com.example.hms.model.UserRoleHospitalAssignment::getId)
            .orElse(null);
    }
}
