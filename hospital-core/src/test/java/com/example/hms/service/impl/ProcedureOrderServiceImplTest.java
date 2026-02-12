package com.example.hms.service.impl;

import com.example.hms.enums.ProcedureOrderStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.procedure.ProcedureOrderRequestDTO;
import com.example.hms.payload.dto.procedure.ProcedureOrderResponseDTO;
import com.example.hms.payload.dto.procedure.ProcedureOrderUpdateDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import com.example.hms.repository.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcedureOrderServiceImplTest {

    @Mock private ProcedureOrderRepository procedureOrderRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private EncounterRepository encounterRepository;

    @InjectMocks private ProcedureOrderServiceImpl service;

    private UUID patientId, hospitalId, staffId, orderId;
    private Patient patient;
    private Hospital hospital;
    private Staff staff;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        patient = new Patient(); patient.setId(patientId); patient.setFirstName("Jane"); patient.setLastName("Doe");
        hospital = new Hospital(); hospital.setId(hospitalId); hospital.setName("Test Hospital");
        staff = new Staff(); staff.setId(staffId);
    }

    private ProcedureOrder buildOrder(ProcedureOrderStatus status) {
        ProcedureOrder o = ProcedureOrder.builder()
            .patient(patient).hospital(hospital).orderingProvider(staff)
            .procedureName("Appendectomy").status(status).build();
        o.setId(orderId);
        return o;
    }

    @Test void createProcedureOrder_success() {
        ProcedureOrderRequestDTO r = new ProcedureOrderRequestDTO();
        r.setPatientId(patientId); r.setHospitalId(hospitalId);
        r.setProcedureName("Appendectomy"); r.setProcedureCode("44970");
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(procedureOrderRepository.save(any())).thenAnswer(i -> { ProcedureOrder o = i.getArgument(0); o.setId(orderId); return o; });
        ProcedureOrderResponseDTO result = service.createProcedureOrder(r, staffId);
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(ProcedureOrderStatus.ORDERED);
        assertThat(result.getProcedureName()).isEqualTo("Appendectomy");
    }

    @Test void createProcedureOrder_patientNotFound() {
        ProcedureOrderRequestDTO r = new ProcedureOrderRequestDTO();
        r.setPatientId(patientId); r.setHospitalId(hospitalId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createProcedureOrder(r, staffId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getProcedureOrder_success() {
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.ORDERED)));
        ProcedureOrderResponseDTO result = service.getProcedureOrder(orderId);
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(orderId);
    }

    @Test void getProcedureOrder_notFound() {
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProcedureOrder(orderId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getProcedureOrdersForPatient() {
        when(procedureOrderRepository.findByPatient_IdOrderByOrderedAtDesc(patientId)).thenReturn(List.of(buildOrder(ProcedureOrderStatus.ORDERED)));
        assertThat(service.getProcedureOrdersForPatient(patientId)).hasSize(1);
    }

    @Test void getProcedureOrdersForHospital_withStatus() {
        when(procedureOrderRepository.findByHospital_IdAndStatusOrderByScheduledDatetimeAsc(hospitalId, ProcedureOrderStatus.SCHEDULED))
            .thenReturn(List.of(buildOrder(ProcedureOrderStatus.SCHEDULED)));
        assertThat(service.getProcedureOrdersForHospital(hospitalId, ProcedureOrderStatus.SCHEDULED)).hasSize(1);
    }

    @Test void getProcedureOrdersOrderedBy() {
        when(procedureOrderRepository.findByOrderingProvider_IdOrderByOrderedAtDesc(staffId)).thenReturn(List.of());
        assertThat(service.getProcedureOrdersOrderedBy(staffId)).isEmpty();
    }

    @Test void getProcedureOrdersScheduledBetween() {
        LocalDateTime start = LocalDateTime.now(); LocalDateTime end = start.plusDays(7);
        when(procedureOrderRepository.findByHospital_IdAndScheduledDatetimeBetween(hospitalId, start, end)).thenReturn(List.of());
        assertThat(service.getProcedureOrdersScheduledBetween(hospitalId, start, end)).isEmpty();
    }

    @Test void updateProcedureOrder_success() {
        ProcedureOrderUpdateDTO u = new ProcedureOrderUpdateDTO();
        u.setStatus(ProcedureOrderStatus.COMPLETED);
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.SCHEDULED)));
        when(procedureOrderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        ProcedureOrderResponseDTO r = service.updateProcedureOrder(orderId, u);
        assertThat(r.getStatus()).isEqualTo(ProcedureOrderStatus.COMPLETED);
    }

    @Test void updateProcedureOrder_completedOrder_throws() {
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.COMPLETED)));
        ProcedureOrderUpdateDTO u = new ProcedureOrderUpdateDTO();
        assertThatThrownBy(() -> service.updateProcedureOrder(orderId, u)).isInstanceOf(BusinessException.class);
    }

    @Test void updateProcedureOrder_cancelledOrder_throws() {
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.CANCELLED)));
        ProcedureOrderUpdateDTO u = new ProcedureOrderUpdateDTO();
        assertThatThrownBy(() -> service.updateProcedureOrder(orderId, u)).isInstanceOf(BusinessException.class);
    }

    @Test void updateProcedureOrder_scheduledDateSetsStatus() {
        ProcedureOrderUpdateDTO u = new ProcedureOrderUpdateDTO();
        u.setScheduledDatetime(LocalDateTime.now().plusDays(3));
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.ORDERED)));
        when(procedureOrderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        ProcedureOrderResponseDTO r = service.updateProcedureOrder(orderId, u);
        assertThat(r.getStatus()).isEqualTo(ProcedureOrderStatus.SCHEDULED);
    }

    @Test void cancelProcedureOrder_success() {
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.ORDERED)));
        when(procedureOrderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        ProcedureOrderResponseDTO r = service.cancelProcedureOrder(orderId, "No longer needed");
        assertThat(r.getStatus()).isEqualTo(ProcedureOrderStatus.CANCELLED);
        assertThat(r.getCancellationReason()).isEqualTo("No longer needed");
    }

    @Test void cancelProcedureOrder_alreadyCompleted_throws() {
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.COMPLETED)));
        assertThatThrownBy(() -> service.cancelProcedureOrder(orderId, "r")).isInstanceOf(BusinessException.class);
    }

    @Test void cancelProcedureOrder_alreadyCancelled_throws() {
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.CANCELLED)));
        assertThatThrownBy(() -> service.cancelProcedureOrder(orderId, "r")).isInstanceOf(BusinessException.class);
    }

    @Test void getPendingConsentOrders() {
        ProcedureOrder o = buildOrder(ProcedureOrderStatus.SCHEDULED);
        o.setConsentObtained(false);
        when(procedureOrderRepository.findByStatusAndConsentObtainedFalse(ProcedureOrderStatus.SCHEDULED)).thenReturn(List.of(o));
        assertThat(service.getPendingConsentOrders(hospitalId)).hasSize(1);
    }
}
