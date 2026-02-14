package com.example.hms.service;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.mapper.ImagingReportMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.ImagingReportStatusHistory;
import com.example.hms.model.Organization;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingReportStatusUpdateRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingReportUpsertRequestDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.impl.ImagingReportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImagingReportServiceImplTest {

    @Mock
    private ImagingReportRepository imagingReportRepository;
    @Mock
    private ImagingOrderRepository imagingOrderRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private DepartmentRepository departmentRepository;
    @Mock
    private StaffRepository staffRepository;
    @Spy
    private ImagingReportMapper imagingReportMapper = new ImagingReportMapper();

    @InjectMocks
    private ImagingReportServiceImpl imagingReportService;

    private UUID hospitalId;
    private UUID orderId;
    private UUID reportId;
    private Hospital hospital;
    private ImagingOrder imagingOrder;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        reportId = UUID.randomUUID();

        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setOrganization(organization);

        imagingOrder = new ImagingOrder();
        imagingOrder.setId(orderId);
        imagingOrder.setHospital(hospital);
    }

    @Test
    void createReportIncrementsVersionAndDemotesExistingLatest() {
        ImagingReportUpsertRequestDTO request = ImagingReportUpsertRequestDTO.builder()
            .imagingOrderId(orderId)
            .hospitalId(hospitalId)
            .reportNumber("IR-100")
            .modality(ImagingModality.MRI)
            .build();

        ImagingReport existingLatest = new ImagingReport();
        existingLatest.setId(UUID.randomUUID());
        existingLatest.setImagingOrder(imagingOrder);
        existingLatest.setHospital(hospital);
        existingLatest.setReportVersion(1);
        existingLatest.setLatestVersion(true);

        ImagingReportResponseDTO responseDTO = ImagingReportResponseDTO.builder()
            .id(UUID.randomUUID())
            .build();

        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(imagingOrder));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(imagingReportRepository.findByReportNumberAndHospital_Id("IR-100", hospitalId)).thenReturn(Optional.empty());
        when(imagingReportRepository.findTopByImagingOrder_IdOrderByReportVersionDesc(orderId)).thenReturn(Optional.of(existingLatest));
        when(imagingReportRepository.findFirstByImagingOrder_IdAndLatestVersionIsTrue(orderId)).thenReturn(Optional.of(existingLatest));
        when(imagingReportRepository.save(any(ImagingReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doReturn(responseDTO).when(imagingReportMapper).toResponseDTO(any(ImagingReport.class));

        ImagingReportResponseDTO result = imagingReportService.createReport(request);

        assertThat(existingLatest.getLatestVersion()).isFalse();
        ArgumentCaptor<ImagingReport> captor = ArgumentCaptor.forClass(ImagingReport.class);
        verify(imagingReportRepository, atLeastOnce()).save(captor.capture());
        ImagingReport created = captor.getAllValues().stream()
            .filter(report -> report != existingLatest)
            .reduce((first, second) -> second)
            .orElseThrow();

        assertThat(created.getReportVersion()).isEqualTo(2);
        assertThat(created.getLatestVersion()).isTrue();
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void updateReportStatusAppendsHistoryWithStaffMetadata() {
        ImagingReport report = new ImagingReport();
        report.setId(reportId);
        report.setImagingOrder(imagingOrder);
        report.setHospital(hospital);
        report.setReportStatus(ImagingReportStatus.PRELIMINARY);
        report.setStatusHistory(new ArrayList<>());

        UUID staffId = UUID.randomUUID();
    Staff staff = new Staff();
    staff.setId(staffId);
    staff.setHospital(hospital);
    User user = new User();
    user.setFirstName("Dr.");
    user.setLastName("Radiologist");
    staff.setUser(user);

        ImagingReportStatusUpdateRequestDTO request = ImagingReportStatusUpdateRequestDTO.builder()
            .status(ImagingReportStatus.FINAL)
            .statusReason("Reviewed and signed")
            .changedByStaffId(staffId)
            .clientSource("portal")
            .notes("Signed off")
            .build();

        ImagingReportResponseDTO responseDTO = ImagingReportResponseDTO.builder().id(reportId).build();

        when(imagingReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(imagingReportRepository.save(report)).thenReturn(report);
        doReturn(responseDTO).when(imagingReportMapper).toResponseDTO(report);

        ImagingReportResponseDTO result = imagingReportService.updateReportStatus(reportId, request);

        assertThat(report.getReportStatus()).isEqualTo(ImagingReportStatus.FINAL);
        assertThat(report.getStatusHistory()).hasSize(1);
        ImagingReportStatusHistory history = report.getStatusHistory().get(0);
        assertThat(history.getStatus()).isEqualTo(ImagingReportStatus.FINAL);
        assertThat(history.getStatusReason()).isEqualTo("Reviewed and signed");
        assertThat(history.getChangedBy()).isSameAs(staff);
        assertThat(history.getChangedByName()).isEqualTo("Dr. Radiologist");
        assertThat(history.getClientSource()).isEqualTo("portal");
        assertThat(history.getNotes()).isEqualTo("Signed off");
        assertThat(history.getChangedAt()).isBeforeOrEqualTo(LocalDateTime.now());
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void updateReportPromotesVersionToLatestAndDemotesPrevious() {
        ImagingReport report = new ImagingReport();
        report.setId(reportId);
        report.setImagingOrder(imagingOrder);
        report.setHospital(hospital);
        report.setLatestVersion(false);
        report.setStatusHistory(new ArrayList<>());

        ImagingReport otherLatest = new ImagingReport();
        otherLatest.setId(UUID.randomUUID());
        otherLatest.setImagingOrder(imagingOrder);
        otherLatest.setHospital(hospital);
        otherLatest.setLatestVersion(true);

        ImagingReportUpsertRequestDTO request = ImagingReportUpsertRequestDTO.builder()
            .imagingOrderId(orderId)
            .hospitalId(hospitalId)
            .latestVersion(Boolean.TRUE)
            .build();

        ImagingReportResponseDTO responseDTO = ImagingReportResponseDTO.builder().id(reportId).build();

    when(imagingReportRepository.findById(reportId)).thenReturn(Optional.of(report));
    when(imagingReportRepository.findFirstByImagingOrder_IdAndLatestVersionIsTrue(orderId)).thenReturn(Optional.of(otherLatest));
    when(imagingReportRepository.save(any(ImagingReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doReturn(responseDTO).when(imagingReportMapper).toResponseDTO(report);

        ImagingReportResponseDTO result = imagingReportService.updateReport(reportId, request);

        assertThat(report.getLatestVersion()).isTrue();
        assertThat(otherLatest.getLatestVersion()).isFalse();
        assertThat(result).isSameAs(responseDTO);
    }
}
