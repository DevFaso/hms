package com.example.hms.service;

import com.example.hms.enums.UltrasoundOrderStatus;
import com.example.hms.enums.UltrasoundScanType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.UltrasoundMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UltrasoundOrder;
import com.example.hms.model.UltrasoundReport;
import com.example.hms.model.User;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderRequestDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportRequestDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UltrasoundOrderRepository;
import com.example.hms.repository.UltrasoundReportRepository;
import com.example.hms.service.impl.UltrasoundServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UltrasoundServiceImplTest {

    @Mock
    private UltrasoundOrderRepository orderRepository;
    @Mock
    private UltrasoundReportRepository reportRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private UltrasoundMapper ultrasoundMapper;

    @InjectMocks
    private UltrasoundServiceImpl ultrasoundService;

    private UUID patientId;
    private UUID hospitalId;
    private UUID orderId;
    private UUID reportId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void init() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        reportId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(patientId);

        hospital = new Hospital();
        hospital.setId(hospitalId);
    }

    @Test
    void createOrder_setsOrderedByFromStaffUserName() {
        UUID staffUserId = UUID.randomUUID();
        UltrasoundOrderRequestDTO request = UltrasoundOrderRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .scanType(UltrasoundScanType.NUCHAL_TRANSLUCENCY)
            .gestationalAgeAtOrder(12)
            .build();

        UltrasoundOrder orderEntity = new UltrasoundOrder();
        orderEntity.setStatus(UltrasoundOrderStatus.SCHEDULED);

        UltrasoundOrderResponseDTO responseDTO = UltrasoundOrderResponseDTO.builder()
            .id(orderId)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(ultrasoundMapper.toOrderEntity(request, patient, hospital)).thenReturn(orderEntity);
        when(orderRepository.save(orderEntity)).thenReturn(orderEntity);
        when(ultrasoundMapper.toOrderResponseDTO(orderEntity)).thenReturn(responseDTO);

        User staffUser = new User();
        staffUser.setFirstName("Jane");
        staffUser.setLastName("Doe");

        Staff staff = new Staff();
        staff.setUser(staffUser);
        staff.setHospital(hospital);

        when(staffRepository.findByUserIdAndHospitalId(staffUserId, hospitalId))
            .thenReturn(Optional.of(staff));

        UltrasoundOrderResponseDTO result = ultrasoundService.createOrder(request, staffUserId);

        assertThat(orderEntity.getStatus()).isEqualTo(UltrasoundOrderStatus.ORDERED);
        assertThat(orderEntity.getOrderedBy()).isEqualTo("Jane Doe");
        assertThat(result).isSameAs(responseDTO);
        verify(orderRepository).save(orderEntity);
    }

    @Test
    void createOrder_whenStaffMissingLeavesOrderedByUnset() {
        UUID staffUserId = UUID.randomUUID();
        UltrasoundOrderRequestDTO request = UltrasoundOrderRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .scanType(UltrasoundScanType.NUCHAL_TRANSLUCENCY)
            .gestationalAgeAtOrder(12)
            .build();

        UltrasoundOrder orderEntity = new UltrasoundOrder();
        orderEntity.setStatus(UltrasoundOrderStatus.SCHEDULED);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(ultrasoundMapper.toOrderEntity(request, patient, hospital)).thenReturn(orderEntity);
        when(orderRepository.save(orderEntity)).thenReturn(orderEntity);
        when(ultrasoundMapper.toOrderResponseDTO(orderEntity)).thenReturn(UltrasoundOrderResponseDTO.builder().id(orderId).build());
        when(staffRepository.findByUserIdAndHospitalId(staffUserId, hospitalId)).thenReturn(Optional.empty());

        ultrasoundService.createOrder(request, staffUserId);

        assertThat(orderEntity.getOrderedBy()).isNull();
    }

    @Test
    void createOrder_withInvalidGestationalAgeThrowsBusinessException() {
        UltrasoundOrderRequestDTO request = UltrasoundOrderRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .scanType(UltrasoundScanType.GROWTH_SCAN)
            .gestationalAgeAtOrder(22)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));

        assertThatThrownBy(() -> ultrasoundService.createOrder(request, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Growth scan");

        verifyNoInteractions(ultrasoundMapper);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void createOrder_withInvalidAnatomyScanWindowThrowsBusinessException() {
        UltrasoundOrderRequestDTO request = UltrasoundOrderRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .scanType(UltrasoundScanType.ANATOMY_SCAN)
            .gestationalAgeAtOrder(16)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));

        assertThatThrownBy(() -> ultrasoundService.createOrder(request, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Anatomy scan");

        verify(orderRepository, never()).save(any());
    }

    @Test
    void createOrUpdateReport_createsNewReportAndAdvancesOrderStatus() {
        UUID performedBy = UUID.randomUUID();
        UltrasoundReportRequestDTO request = UltrasoundReportRequestDTO.builder()
            .scanDate(LocalDate.now())
            .findingCategory(com.example.hms.enums.UltrasoundFindingCategory.NORMAL)
            .build();

        UltrasoundOrder order = new UltrasoundOrder();
        order.setId(orderId);
        order.setHospital(hospital);
        order.setStatus(UltrasoundOrderStatus.ORDERED);

        UltrasoundReport mappedReport = new UltrasoundReport();
        mappedReport.setHospital(hospital);
        mappedReport.setUltrasoundOrder(order);

        UltrasoundReportResponseDTO responseDTO = UltrasoundReportResponseDTO.builder()
            .ultrasoundOrderId(orderId)
            .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(reportRepository.findByUltrasoundOrderId(orderId)).thenReturn(Optional.empty());
        when(ultrasoundMapper.toReportEntity(request, order, hospital)).thenReturn(mappedReport);
        when(reportRepository.save(mappedReport)).thenReturn(mappedReport);
        when(ultrasoundMapper.toReportResponseDTO(mappedReport)).thenReturn(responseDTO);

        User staffUser = new User();
        staffUser.setFirstName("Alex");
        staffUser.setLastName("Ray");

        Staff staff = new Staff();
        staff.setUser(staffUser);
        staff.setHospital(hospital);

        when(staffRepository.findByUserIdAndHospitalId(performedBy, hospitalId))
            .thenReturn(Optional.of(staff));

        UltrasoundReportResponseDTO result = ultrasoundService.createOrUpdateReport(orderId, request, performedBy);

        assertThat(order.getStatus()).isEqualTo(UltrasoundOrderStatus.IN_PROGRESS);
        assertThat(mappedReport.getScanPerformedBy()).isEqualTo("Alex Ray");
        assertThat(result).isSameAs(responseDTO);
        verify(orderRepository).save(order);
    }

    @Test
    void createOrUpdateReport_updatesExistingReport() {
        UltrasoundReportRequestDTO request = UltrasoundReportRequestDTO.builder()
            .scanDate(LocalDate.now())
            .findingCategory(com.example.hms.enums.UltrasoundFindingCategory.NORMAL)
            .scanPerformedBy("Tech One")
            .build();

        UltrasoundOrder order = new UltrasoundOrder();
        order.setId(orderId);
        order.setHospital(hospital);
        order.setStatus(UltrasoundOrderStatus.SCHEDULED);

        UltrasoundReport existingReport = new UltrasoundReport();
        existingReport.setUltrasoundOrder(order);
        existingReport.setHospital(hospital);
        existingReport.setReportReviewedByProvider(false);

        UltrasoundReportResponseDTO responseDTO = UltrasoundReportResponseDTO.builder()
            .ultrasoundOrderId(orderId)
            .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(reportRepository.findByUltrasoundOrderId(orderId)).thenReturn(Optional.of(existingReport));
        when(reportRepository.save(existingReport)).thenReturn(existingReport);
        when(ultrasoundMapper.toReportResponseDTO(existingReport)).thenReturn(responseDTO);

        UltrasoundReportResponseDTO result = ultrasoundService.createOrUpdateReport(orderId, request, null);

        assertThat(order.getStatus()).isEqualTo(UltrasoundOrderStatus.IN_PROGRESS);
        assertThat(result).isSameAs(responseDTO);
        verify(ultrasoundMapper).updateReportFromRequest(existingReport, request);
        verify(orderRepository).save(order);
    }

    @Test
    void createOrUpdateReport_throwsWhenOrderCancelled() {
        UltrasoundReportRequestDTO request = UltrasoundReportRequestDTO.builder()
            .scanDate(LocalDate.now())
            .findingCategory(com.example.hms.enums.UltrasoundFindingCategory.NORMAL)
            .build();

        UltrasoundOrder order = new UltrasoundOrder();
        order.setId(orderId);
        order.setHospital(hospital);
        order.setStatus(UltrasoundOrderStatus.CANCELLED);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> ultrasoundService.createOrUpdateReport(orderId, request, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cancelled");

        verify(reportRepository, never()).save(any());
    }

    @Test
    void createOrUpdateReport_throwsWhenReportAlreadyReviewed() {
        UltrasoundReportRequestDTO request = UltrasoundReportRequestDTO.builder()
            .scanDate(LocalDate.now())
            .findingCategory(com.example.hms.enums.UltrasoundFindingCategory.NORMAL)
            .build();

        UltrasoundOrder order = new UltrasoundOrder();
        order.setId(orderId);
        order.setHospital(hospital);
        order.setStatus(UltrasoundOrderStatus.IN_PROGRESS);

        UltrasoundReport existingReport = new UltrasoundReport();
        existingReport.setUltrasoundOrder(order);
        existingReport.setHospital(hospital);
        existingReport.setReportReviewedByProvider(true);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(reportRepository.findByUltrasoundOrderId(orderId)).thenReturn(Optional.of(existingReport));

        assertThatThrownBy(() -> ultrasoundService.createOrUpdateReport(orderId, request, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reviewed");

        verify(reportRepository, never()).save(any());
    }

    @Test
    void markReportReviewed_setsReviewerAndCompletesOrder() {
        UUID reviewerId = UUID.randomUUID();

        UltrasoundOrder order = new UltrasoundOrder();
        order.setId(orderId);
        order.setHospital(hospital);
        order.setStatus(UltrasoundOrderStatus.IN_PROGRESS);

        UltrasoundReport report = new UltrasoundReport();
        report.setId(reportId);
        report.setUltrasoundOrder(order);
        report.setHospital(hospital);
        report.setReportReviewedByProvider(null);

        UltrasoundReportResponseDTO responseDTO = UltrasoundReportResponseDTO.builder()
            .id(reportId)
            .reportReviewedByProvider(true)
            .build();

        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(reportRepository.save(report)).thenReturn(report);
        when(orderRepository.save(order)).thenReturn(order);
        when(ultrasoundMapper.toReportResponseDTO(report)).thenReturn(responseDTO);

        Staff staff = new Staff();
        staff.setHospital(hospital);
        staff.setName("Dr. Smith");

        when(staffRepository.findByUserIdAndHospitalId(reviewerId, hospitalId))
            .thenReturn(Optional.of(staff));

        UltrasoundReportResponseDTO result = ultrasoundService.markReportReviewed(reportId, reviewerId);

        assertThat(report.getReportReviewedByProvider()).isTrue();
        assertThat(report.getReportFinalizedBy()).isEqualTo("Dr. Smith");
        assertThat(order.getStatus()).isEqualTo(UltrasoundOrderStatus.COMPLETED);
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void markReportReviewed_withoutMatchingStaffKeepsFinalizedByNull() {
        UUID reviewerId = UUID.randomUUID();

        UltrasoundOrder order = new UltrasoundOrder();
        order.setId(orderId);
        order.setHospital(hospital);
        order.setStatus(UltrasoundOrderStatus.IN_PROGRESS);

        UltrasoundReport report = new UltrasoundReport();
        report.setId(reportId);
        report.setUltrasoundOrder(order);
        report.setHospital(hospital);
        report.setReportReviewedByProvider(null);

        UltrasoundReportResponseDTO responseDTO = UltrasoundReportResponseDTO.builder().id(reportId).build();

        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(reportRepository.save(report)).thenReturn(report);
        when(orderRepository.save(order)).thenReturn(order);
        when(staffRepository.findByUserIdAndHospitalId(reviewerId, hospitalId)).thenReturn(Optional.empty());
        when(ultrasoundMapper.toReportResponseDTO(report)).thenReturn(responseDTO);

        UltrasoundReportResponseDTO result = ultrasoundService.markReportReviewed(reportId, reviewerId);

        assertThat(report.getReportReviewedByProvider()).isTrue();
        assertThat(report.getReportFinalizedBy()).isNull();
        assertThat(order.getStatus()).isEqualTo(UltrasoundOrderStatus.COMPLETED);
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void markReportReviewed_throwsWhenAlreadyReviewed() {
        UltrasoundOrder order = new UltrasoundOrder();
        order.setId(orderId);
        order.setHospital(hospital);

        UltrasoundReport report = new UltrasoundReport();
        report.setId(reportId);
        report.setUltrasoundOrder(order);
        report.setHospital(hospital);
        report.setReportReviewedByProvider(true);

        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> ultrasoundService.markReportReviewed(reportId, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already reviewed");

        verify(reportRepository, never()).save(any());
    }

    @Test
    void markPatientNotified_updatesNotificationTimestamp() {
        UltrasoundReport report = new UltrasoundReport();
        report.setId(reportId);
        report.setPatientNotifiedAt(null);

        UltrasoundReportResponseDTO responseDTO = UltrasoundReportResponseDTO.builder()
            .id(reportId)
            .patientNotified(true)
            .build();

        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(reportRepository.save(report)).thenReturn(report);
        when(ultrasoundMapper.toReportResponseDTO(report)).thenReturn(responseDTO);

        UltrasoundReportResponseDTO result = ultrasoundService.markPatientNotified(reportId);

        assertThat(report.getPatientNotifiedAt()).isNotNull();
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void markPatientNotified_throwsWhenAlreadyNotified() {
        UltrasoundReport report = new UltrasoundReport();
        report.setId(reportId);
        report.setPatientNotifiedAt(LocalDateTime.now());

        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> ultrasoundService.markPatientNotified(reportId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already been notified");

        verify(reportRepository, never()).save(any());
    }

    @Test
    void getTemplateMethodsReturnPresetValues() {
        var ntTemplate = ultrasoundService.getNuchalTranslucencyTemplate();
        var anatomyTemplate = ultrasoundService.getAnatomyScanTemplate();

        assertThat(ntTemplate.getGestationalAgeAtScan()).isEqualTo(12);
        assertThat(ntTemplate.getNumberOfFetuses()).isEqualTo(1);
        assertThat(anatomyTemplate.getGestationalAgeAtScan()).isEqualTo(20);
        assertThat(anatomyTemplate.getNumberOfFetuses()).isEqualTo(1);
        assertThat(anatomyTemplate.getAnatomySurveyComplete()).isFalse();
    }

    @Test
    void simpleReadQueriesDelegateToRepositories() {
        UltrasoundOrder order = new UltrasoundOrder();
        UltrasoundOrderResponseDTO orderDto = UltrasoundOrderResponseDTO.builder().id(orderId).build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(ultrasoundMapper.toOrderResponseDTO(order)).thenReturn(orderDto);
        when(orderRepository.findAllByPatientId(patientId)).thenReturn(Collections.singletonList(order));
        when(orderRepository.findByPatientIdAndStatus(patientId, UltrasoundOrderStatus.ORDERED)).thenReturn(Collections.singletonList(order));
        when(orderRepository.findAllByHospitalId(hospitalId)).thenReturn(Collections.singletonList(order));
        when(orderRepository.findPendingOrders(hospitalId)).thenReturn(Collections.singletonList(order));
        when(orderRepository.findAllHighRiskOrders(hospitalId)).thenReturn(Collections.singletonList(order));

        UltrasoundOrderResponseDTO dtoById = ultrasoundService.getOrderById(orderId);
        List<UltrasoundOrderResponseDTO> byPatient = ultrasoundService.getOrdersByPatientId(patientId);
        List<UltrasoundOrderResponseDTO> byPatientStatus = ultrasoundService.getOrdersByPatientIdAndStatus(patientId, UltrasoundOrderStatus.ORDERED);
        List<UltrasoundOrderResponseDTO> byHospital = ultrasoundService.getOrdersByHospitalId(hospitalId);
        List<UltrasoundOrderResponseDTO> pending = ultrasoundService.getPendingOrders(hospitalId);
        List<UltrasoundOrderResponseDTO> highRisk = ultrasoundService.getHighRiskOrders(hospitalId);

        assertThat(dtoById).isSameAs(orderDto);
        assertThat(byPatient).containsExactly(orderDto);
        assertThat(byPatientStatus).containsExactly(orderDto);
        assertThat(byHospital).containsExactly(orderDto);
        assertThat(pending).containsExactly(orderDto);
        assertThat(highRisk).containsExactly(orderDto);

        verify(orderRepository).findById(orderId);
        verify(orderRepository).findAllByPatientId(patientId);
        verify(orderRepository).findByPatientIdAndStatus(patientId, UltrasoundOrderStatus.ORDERED);
        verify(orderRepository).findAllByHospitalId(hospitalId);
        verify(orderRepository).findPendingOrders(hospitalId);
        verify(orderRepository).findAllHighRiskOrders(hospitalId);
    }

    @Test
    void reportLookupMethodsDelegateToRepository() {
        UltrasoundReport report = new UltrasoundReport();
        report.setId(reportId);
        UltrasoundReportResponseDTO responseDTO = UltrasoundReportResponseDTO.builder().id(reportId).build();

        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(reportRepository.findByUltrasoundOrderId(orderId)).thenReturn(Optional.of(report));
        when(reportRepository.findReportsRequiringFollowUp(hospitalId)).thenReturn(Collections.singletonList(report));
        when(reportRepository.findReportsWithAnomalies(hospitalId)).thenReturn(Collections.singletonList(report));
        when(ultrasoundMapper.toReportResponseDTO(report)).thenReturn(responseDTO);

        UltrasoundReportResponseDTO byId = ultrasoundService.getReportById(reportId);
        UltrasoundReportResponseDTO byOrderId = ultrasoundService.getReportByOrderId(orderId);
        List<UltrasoundReportResponseDTO> requiringFollowUp = ultrasoundService.getReportsRequiringFollowUp(hospitalId);
        List<UltrasoundReportResponseDTO> withAnomalies = ultrasoundService.getReportsWithAnomalies(hospitalId);

        assertThat(byId).isSameAs(responseDTO);
        assertThat(byOrderId).isSameAs(responseDTO);
        assertThat(requiringFollowUp).containsExactly(responseDTO);
        assertThat(withAnomalies).containsExactly(responseDTO);

        verify(reportRepository).findById(reportId);
        verify(reportRepository).findByUltrasoundOrderId(orderId);
        verify(reportRepository).findReportsRequiringFollowUp(hospitalId);
        verify(reportRepository).findReportsWithAnomalies(hospitalId);
    }

    @Test
    void cancelOrder_updatesStatusAndReason() {
        String reason = "Patient request";
        UltrasoundOrder order = new UltrasoundOrder();
        order.setStatus(UltrasoundOrderStatus.ORDERED);

        UltrasoundOrderResponseDTO responseDTO = UltrasoundOrderResponseDTO.builder()
            .cancellationReason(reason)
            .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(ultrasoundMapper.toOrderResponseDTO(order)).thenReturn(responseDTO);

        UltrasoundOrderResponseDTO result = ultrasoundService.cancelOrder(orderId, reason);

        assertThat(order.getStatus()).isEqualTo(UltrasoundOrderStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo(reason);
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void cancelOrder_throwsWhenCompleted() {
        UltrasoundOrder order = new UltrasoundOrder();
        order.setStatus(UltrasoundOrderStatus.COMPLETED);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> ultrasoundService.cancelOrder(orderId, "reason"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("completed");
    }

    @Test
    void updateOrder_changesHospitalAndDelegatesToMapper() {
        UUID newHospitalId = UUID.randomUUID();
        Hospital newHospital = new Hospital();
        newHospital.setId(newHospitalId);

        UltrasoundOrder order = new UltrasoundOrder();
        order.setId(orderId);
        order.setHospital(hospital);
        order.setStatus(UltrasoundOrderStatus.ORDERED);

        UltrasoundOrderRequestDTO request = UltrasoundOrderRequestDTO.builder()
            .hospitalId(newHospitalId)
            .scanType(UltrasoundScanType.ANATOMY_SCAN)
            .build();

        UltrasoundOrderResponseDTO responseDTO = UltrasoundOrderResponseDTO.builder().id(orderId).build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(hospitalRepository.findById(newHospitalId)).thenReturn(Optional.of(newHospital));
        when(orderRepository.save(order)).thenReturn(order);
        when(ultrasoundMapper.toOrderResponseDTO(order)).thenReturn(responseDTO);

        UltrasoundOrderResponseDTO result = ultrasoundService.updateOrder(orderId, request);

        assertThat(order.getHospital()).isEqualTo(newHospital);
        assertThat(result).isSameAs(responseDTO);
        verify(ultrasoundMapper).updateOrderFromRequest(order, request);
    }

    @Test
    void updateOrder_throwsWhenCancelled() {
        UltrasoundOrder order = new UltrasoundOrder();
        order.setStatus(UltrasoundOrderStatus.CANCELLED);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        UltrasoundOrderRequestDTO request = UltrasoundOrderRequestDTO.builder().build();
        assertThatThrownBy(() -> ultrasoundService.updateOrder(orderId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cancelled");
    }

    @Test
    void getOrderById_throwsWhenMissing() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ultrasoundService.getOrderById(orderId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Ultrasound order not found");
    }

    @Test
    void getReportById_throwsWhenMissing() {
        when(reportRepository.findById(reportId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ultrasoundService.getReportById(reportId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Ultrasound report not found");
    }

    @Test
    void getReportByOrderId_throwsWhenMissing() {
        when(reportRepository.findByUltrasoundOrderId(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ultrasoundService.getReportByOrderId(orderId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Ultrasound report not found for order");
    }
}