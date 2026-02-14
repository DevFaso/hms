package com.example.hms.service.impl;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ImagingReportMapper;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.ImagingReportStatusHistory;
import com.example.hms.model.Organization;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingReportStatusUpdateRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingReportUpsertRequestDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.ImagingReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ImagingReportServiceImpl implements ImagingReportService {

    private final ImagingReportRepository imagingReportRepository;
    private final ImagingOrderRepository imagingOrderRepository;
    private final HospitalRepository hospitalRepository;
    private final OrganizationRepository organizationRepository;
    private final DepartmentRepository departmentRepository;
    private final StaffRepository staffRepository;
    private final ImagingReportMapper imagingReportMapper;

    @Override
    public ImagingReportResponseDTO createReport(ImagingReportUpsertRequestDTO request) {
        validateCreateRequest(request);
        ImagingOrder imagingOrder = resolveImagingOrder(request.getImagingOrderId());
        Hospital hospital = resolveHospital(request.getHospitalId(), imagingOrder);
        Organization organization = resolveOrganization(request.getOrganizationId(), hospital);
        Department department = resolveDepartment(request.getDepartmentId(), hospital);

        ImagingReport report = new ImagingReport();
        report.setImagingOrder(imagingOrder);
        report.setHospital(hospital);
        report.setOrganization(organization);
        report.setDepartment(department);

    applyStaffAssociations(report, request);
    imagingReportMapper.updateReportFromRequest(report, request);

    enforceUniqueReportNumber(report.getReportNumber(), hospital != null ? hospital.getId() : null, null);
    assignVersioningForCreate(report, imagingOrder.getId(), request.getReportVersion() != null);

        ImagingReport saved = imagingReportRepository.save(report);
        return imagingReportMapper.toResponseDTO(saved);
    }

    @Override
    public ImagingReportResponseDTO updateReport(UUID reportId, ImagingReportUpsertRequestDTO request) {
        if (request == null) {
            throw new BusinessException("Update request payload is required.");
        }

        ImagingReport report = getReportEntity(reportId);
        ImagingOrder imagingOrder = ensureOrderAssociation(report, request);
        Hospital hospital = resolveHospitalForUpdate(report, request, imagingOrder);
        applyOrganizationContext(report, request, hospital);
        applyDepartmentContext(report, request, hospital);
    applyStaffAssociations(report, request);

    ImagingReportStatus previousStatus = report.getReportStatus();
    Boolean previousLatest = report.getLatestVersion();

        imagingReportMapper.updateReportFromRequest(report, request);
        enforceUniqueReportNumber(report.getReportNumber(), hospital != null ? hospital.getId() : null, report.getId());
    handleLatestVersionToggle(report, request, previousLatest);

        if (request.getReportStatus() != null && request.getReportStatus() != previousStatus) {
            appendStatusHistory(report, request.getReportStatus(), null, null, "Report updated", null, null);
        }

        ImagingReport saved = imagingReportRepository.save(report);
        return imagingReportMapper.toResponseDTO(saved);
    }

    @Override
    public ImagingReportResponseDTO updateReportStatus(UUID reportId, ImagingReportStatusUpdateRequestDTO request) {
        if (request == null || request.getStatus() == null) {
            throw new BusinessException("Status update payload with status is required.");
        }

        ImagingReport report = getReportEntity(reportId);
    Staff changedBy = resolveStaff(request.getChangedByStaffId(), report.getHospital());

        report.setReportStatus(request.getStatus());
        report.setLastStatusSyncedAt(LocalDateTime.now());

        appendStatusHistory(report, request.getStatus(), request.getStatusReason(), changedBy,
            request.getChangedByName(), request.getClientSource(), request.getNotes());

        ImagingReport saved = imagingReportRepository.save(report);
        return imagingReportMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ImagingReportResponseDTO getReport(UUID reportId) {
        ImagingReport report = getReportEntity(reportId);
        return imagingReportMapper.toResponseDTO(report);
    }

    @Override
    @Transactional(readOnly = true)
    public ImagingReportResponseDTO getLatestReportForOrder(UUID imagingOrderId) {
        ImagingReport report = imagingReportRepository.findFirstByImagingOrder_IdAndLatestVersionIsTrue(imagingOrderId)
            .orElseGet(() -> imagingReportRepository.findTopByImagingOrder_IdOrderByReportVersionDesc(imagingOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("No imaging reports found for imaging order %s".formatted(imagingOrderId))));
        return imagingReportMapper.toResponseDTO(report);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImagingReportResponseDTO> getReportsForOrder(UUID imagingOrderId) {
        return imagingReportRepository.findByImagingOrder_IdOrderByReportVersionDesc(imagingOrderId).stream()
            .map(imagingReportMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImagingReportResponseDTO> getReportsByHospitalAndStatus(UUID hospitalId, ImagingReportStatus status) {
        if (hospitalId == null) {
            throw new BusinessException("Hospital ID is required to filter imaging reports by status.");
        }
        if (status == null) {
            throw new BusinessException("Status filter is required to list imaging reports.");
        }
        return imagingReportRepository.findByHospital_IdAndReportStatusOrderByPerformedAtDesc(hospitalId, status).stream()
            .map(imagingReportMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImagingReportResponseDTO> getReportsByHospitalAndModality(UUID hospitalId, ImagingModality modality) {
        if (hospitalId == null) {
            throw new BusinessException("Hospital ID is required to filter imaging reports by modality.");
        }
        if (modality == null) {
            throw new BusinessException("Modality filter is required to list imaging reports.");
        }
        return imagingReportRepository.findByHospital_IdAndModalityOrderByPerformedAtDesc(hospitalId, modality).stream()
            .map(imagingReportMapper::toResponseDTO)
            .toList();
    }

    private void validateCreateRequest(ImagingReportUpsertRequestDTO request) {
        if (request == null) {
            throw new BusinessException("Imaging report payload is required.");
        }
        if (request.getImagingOrderId() == null) {
            throw new BusinessException("Imaging order ID is required to create a report.");
        }
    }

    private ImagingReport getReportEntity(UUID reportId) {
        return imagingReportRepository.findById(reportId)
            .orElseThrow(() -> new ResourceNotFoundException("Imaging report not found with id %s".formatted(reportId)));
    }

    private ImagingOrder resolveImagingOrder(UUID imagingOrderId) {
        return imagingOrderRepository.findById(imagingOrderId)
            .orElseThrow(() -> new ResourceNotFoundException("Imaging order not found with id %s".formatted(imagingOrderId)));
    }

    private ImagingOrder ensureOrderAssociation(ImagingReport report, ImagingReportUpsertRequestDTO request) {
        if (request.getImagingOrderId() != null && (report.getImagingOrder() == null
            || !Objects.equals(report.getImagingOrder().getId(), request.getImagingOrderId()))) {
            ImagingOrder imagingOrder = resolveImagingOrder(request.getImagingOrderId());
            report.setImagingOrder(imagingOrder);
            return imagingOrder;
        }
        if (report.getImagingOrder() == null) {
            throw new BusinessException("Imaging report must remain associated with an imaging order.");
        }
        return report.getImagingOrder();
    }

    private Hospital resolveHospital(UUID hospitalId, ImagingOrder imagingOrder) {
        Hospital hospital;
        if (hospitalId != null) {
            hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with id %s".formatted(hospitalId)));
        } else if (imagingOrder.getHospital() != null) {
            hospital = imagingOrder.getHospital();
        } else {
            throw new BusinessException("Hospital context is required for an imaging report.");
        }
        validateOrderHospitalMatch(imagingOrder, hospital);
        return hospital;
    }

    private Hospital resolveHospitalForUpdate(ImagingReport report, ImagingReportUpsertRequestDTO request, ImagingOrder imagingOrder) {
        if (request.getHospitalId() != null && (report.getHospital() == null
            || !Objects.equals(report.getHospital().getId(), request.getHospitalId()))) {
            Hospital hospital = hospitalRepository.findById(request.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with id %s".formatted(request.getHospitalId())));
            validateOrderHospitalMatch(imagingOrder, hospital);
            report.setHospital(hospital);
            return hospital;
        }
        if (report.getHospital() == null) {
            Hospital hospital = resolveHospital(null, imagingOrder);
            report.setHospital(hospital);
            return hospital;
        }
        validateOrderHospitalMatch(imagingOrder, report.getHospital());
        return report.getHospital();
    }

    private Organization resolveOrganization(UUID organizationId, Hospital hospital) {
        if (organizationId == null) {
            return hospital != null ? hospital.getOrganization() : null;
        }
        Organization organization = organizationRepository.findById(organizationId)
            .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id %s".formatted(organizationId)));
        if (hospital != null && hospital.getOrganization() != null
            && !Objects.equals(hospital.getOrganization().getId(), organization.getId())) {
            throw new BusinessException("Organization must match the hospital's organization context.");
        }
        return organization;
    }

    private void applyOrganizationContext(ImagingReport report, ImagingReportUpsertRequestDTO request, Hospital hospital) {
        if (request.getOrganizationId() != null) {
            Organization organization = resolveOrganization(request.getOrganizationId(), hospital);
            report.setOrganization(organization);
        } else if (report.getOrganization() == null && hospital != null) {
            report.setOrganization(hospital.getOrganization());
        }
    }

    private Department resolveDepartment(UUID departmentId, Hospital hospital) {
        if (departmentId == null) {
            return null;
        }
        Department department = departmentRepository.findById(departmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Department not found with id %s".formatted(departmentId)));
        if (hospital != null && department.getHospital() != null
            && !Objects.equals(department.getHospital().getId(), hospital.getId())) {
            throw new BusinessException("Department must belong to the same hospital as the imaging report.");
        }
        return department;
    }

    private void applyDepartmentContext(ImagingReport report, ImagingReportUpsertRequestDTO request, Hospital hospital) {
        if (request.getDepartmentId() != null) {
            Department department = resolveDepartment(request.getDepartmentId(), hospital);
            report.setDepartment(department);
        }
    }

    private Staff resolveStaff(UUID staffId, Hospital hospital) {
        if (staffId == null) {
            return null;
        }
        Staff staff = staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException("Staff not found with id %s".formatted(staffId)));
        if (hospital != null && staff.getHospital() != null
            && !Objects.equals(staff.getHospital().getId(), hospital.getId())) {
            throw new BusinessException("Staff must belong to the same hospital context as the imaging report.");
        }
        return staff;
    }

    private void applyStaffAssociations(ImagingReport report, ImagingReportUpsertRequestDTO request) {
        if (request == null) {
            return;
        }
        Hospital hospital = report.getHospital();
        if (request.getPerformedByStaffId() != null) {
            report.setPerformedBy(resolveStaff(request.getPerformedByStaffId(), hospital));
        }
        if (request.getInterpretingProviderId() != null) {
            report.setInterpretingProvider(resolveStaff(request.getInterpretingProviderId(), hospital));
        }
        if (request.getSignedByStaffId() != null) {
            report.setSignedBy(resolveStaff(request.getSignedByStaffId(), hospital));
        }
        if (request.getCriticalResultAckByStaffId() != null) {
            report.setCriticalResultAcknowledgedBy(resolveStaff(request.getCriticalResultAckByStaffId(), hospital));
        }
    }

    private void assignVersioningForCreate(ImagingReport report, UUID imagingOrderId, boolean versionProvided) {
        int nextVersion = imagingReportRepository.findTopByImagingOrder_IdOrderByReportVersionDesc(imagingOrderId)
            .map(existing -> Optional.ofNullable(existing.getReportVersion()).orElse(0) + 1)
            .orElse(1);

        if (!versionProvided || report.getReportVersion() == null || report.getReportVersion() <= 0) {
            report.setReportVersion(nextVersion);
        }
        if (report.getLatestVersion() == null) {
            report.setLatestVersion(Boolean.TRUE);
        }
        if (Boolean.TRUE.equals(report.getLatestVersion())) {
            markExistingLatestAsHistorical(imagingOrderId, null);
        }
    }

    private void handleLatestVersionToggle(ImagingReport report,
                                           ImagingReportUpsertRequestDTO request,
                                           Boolean previousLatest) {
        if (request.getLatestVersion() == null || report.getImagingOrder() == null) {
            return;
        }
        boolean desiredLatest = Boolean.TRUE.equals(request.getLatestVersion());
        boolean wasLatest = Boolean.TRUE.equals(previousLatest);
        boolean currentlyLatest = Boolean.TRUE.equals(report.getLatestVersion());

        if (desiredLatest) {
            if (!wasLatest || !currentlyLatest) {
                report.setLatestVersion(true);
                markExistingLatestAsHistorical(report.getImagingOrder().getId(), report.getId());
            }
        } else if (wasLatest || currentlyLatest) {
            report.setLatestVersion(false);
        }
    }

    private void markExistingLatestAsHistorical(UUID imagingOrderId, UUID excludeReportId) {
        imagingReportRepository.findFirstByImagingOrder_IdAndLatestVersionIsTrue(imagingOrderId)
            .filter(existing -> excludeReportId == null || !Objects.equals(existing.getId(), excludeReportId))
            .ifPresent(existing -> {
                existing.setLatestVersion(false);
                imagingReportRepository.save(existing);
            });
    }

    private void enforceUniqueReportNumber(String reportNumber, UUID hospitalId, UUID currentReportId) {
        if (!StringUtils.hasText(reportNumber) || hospitalId == null) {
            return;
        }
        imagingReportRepository.findByReportNumberAndHospital_Id(reportNumber, hospitalId)
            .filter(existing -> currentReportId == null || !Objects.equals(existing.getId(), currentReportId))
            .ifPresent(existing -> {
                throw new BusinessException("Report number %s already exists for this hospital.".formatted(reportNumber));
            });
    }

    private void validateOrderHospitalMatch(ImagingOrder imagingOrder, Hospital hospital) {
        if (imagingOrder == null || hospital == null || imagingOrder.getHospital() == null) {
            return;
        }
        if (!Objects.equals(imagingOrder.getHospital().getId(), hospital.getId())) {
            throw new BusinessException("Hospital must match the imaging order's hospital context.");
        }
    }

    private void appendStatusHistory(ImagingReport report,
                                     ImagingReportStatus status,
                                     String statusReason,
                                     Staff changedBy,
                                     String changedByName,
                                     String clientSource,
                                     String notes) {
        if (status == null) {
            return;
        }
        if (report.getStatusHistory() == null) {
            report.setStatusHistory(new ArrayList<>());
        }
        ImagingReportStatusHistory history = new ImagingReportStatusHistory();
        history.setImagingReport(report);
        history.setImagingOrder(report.getImagingOrder());
        history.setStatus(status);
        history.setStatusReason(statusReason);
        history.setChangedBy(changedBy);
        history.setClientSource(clientSource);
        history.setNotes(notes);
        history.setChangedByName(resolveStaffDisplayName(changedBy, changedByName));
        history.setChangedAt(LocalDateTime.now());

        report.getStatusHistory().add(history);
    }

    private String resolveStaffDisplayName(Staff staff, String fallbackName) {
        if (staff != null) {
            if (StringUtils.hasText(staff.getFullName())) {
                return staff.getFullName();
            }
            if (StringUtils.hasText(staff.getName())) {
                return staff.getName();
            }
        }
        return fallbackName;
    }
}
