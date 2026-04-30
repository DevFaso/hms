package com.example.hms.service.impl;

import com.example.hms.enums.AdmissionType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AdmissionOrderSetMapper;
import com.example.hms.model.Admission;
import com.example.hms.model.AdmissionOrderSet;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
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
import com.example.hms.service.orderset.DispatchResult;
import com.example.hms.service.orderset.OrderSetApplicationContext;
import com.example.hms.service.orderset.OrderSetItemDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdmissionOrderSetServiceImplTest {

    private final AdmissionOrderSetRepository orderSetRepo = mock(AdmissionOrderSetRepository.class);
    private final AdmissionRepository admissionRepo = mock(AdmissionRepository.class);
    private final HospitalRepository hospitalRepo = mock(HospitalRepository.class);
    private final DepartmentRepository departmentRepo = mock(DepartmentRepository.class);
    private final StaffRepository staffRepo = mock(StaffRepository.class);
    private final AdmissionOrderSetMapper mapper = new AdmissionOrderSetMapper();
    private final OrderSetItemDispatcher dispatcher = mock(OrderSetItemDispatcher.class);

    private AdmissionOrderSetServiceImpl service;

    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID STAFF_ID = UUID.randomUUID();
    private static final UUID ADMISSION_ID = UUID.randomUUID();
    private static final UUID ORDER_SET_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdmissionOrderSetServiceImpl(
            orderSetRepo, admissionRepo, hospitalRepo, departmentRepo, staffRepo,
            mapper, dispatcher
        );
    }

    private static Hospital hospital() {
        Hospital h = new Hospital();
        h.setId(HOSPITAL_ID);
        h.setName("Test Hospital");
        return h;
    }

    private static Staff staff() {
        Staff s = new Staff();
        s.setId(STAFF_ID);
        s.setName("Dr Test");
        return s;
    }

    private static AdmissionOrderSet existing() {
        AdmissionOrderSet o = new AdmissionOrderSet();
        o.setId(ORDER_SET_ID);
        o.setHospital(hospital());
        o.setName("Sepsis bundle");
        o.setDescription("Hour-1 sepsis");
        o.setAdmissionType(AdmissionType.EMERGENCY);
        o.setActive(true);
        o.setVersion(1);
        o.setOrderItems(List.of(Map.of("orderType", "LAB", "orderName", "Lactate")));
        o.setCreatedBy(staff());
        return o;
    }

    private static AdmissionOrderSetRequestDTO sampleRequest() {
        AdmissionOrderSetRequestDTO r = new AdmissionOrderSetRequestDTO();
        r.setName("Sepsis bundle");
        r.setDescription("Updated");
        r.setAdmissionType(AdmissionType.EMERGENCY);
        r.setHospitalId(HOSPITAL_ID);
        r.setOrderItems(List.of(Map.of("orderType", "MEDICATION", "medicationName", "Ceftriaxone")));
        r.setCreatedByStaffId(STAFF_ID);
        return r;
    }

    @Test
    void getByIdThrowsWhenMissing() {
        when(orderSetRepo.findById(ORDER_SET_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(ORDER_SET_ID))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createPersistsV1Template() {
        when(hospitalRepo.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital()));
        when(staffRepo.findById(STAFF_ID)).thenReturn(Optional.of(staff()));
        when(orderSetRepo.save(any())).thenAnswer(inv -> {
            AdmissionOrderSet o = inv.getArgument(0);
            o.setId(UUID.randomUUID());
            return o;
        });

        AdmissionOrderSetResponseDTO dto = service.create(sampleRequest());
        assertThat(dto.getVersion()).isEqualTo(1);

        ArgumentCaptor<AdmissionOrderSet> captor = ArgumentCaptor.forClass(AdmissionOrderSet.class);
        verify(orderSetRepo).save(captor.capture());
        AdmissionOrderSet saved = captor.getValue();
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getActive()).isTrue();
        assertThat(saved.getParentOrderSet()).isNull();
        assertThat(saved.getCreatedBy().getId()).isEqualTo(STAFF_ID);
    }

    @Test
    void updateFreezesParentAndCreatesNewActiveVersion() {
        when(orderSetRepo.findById(ORDER_SET_ID)).thenReturn(Optional.of(existing()));
        when(staffRepo.findById(STAFF_ID)).thenReturn(Optional.of(staff()));
        when(orderSetRepo.save(any())).thenAnswer(inv -> {
            AdmissionOrderSet o = inv.getArgument(0);
            if (o.getId() == null) o.setId(UUID.randomUUID());
            return o;
        });

        AdmissionOrderSetResponseDTO updated = service.update(ORDER_SET_ID, sampleRequest());

        assertThat(updated.getVersion()).isEqualTo(2);
        assertThat(updated.getActive()).isTrue();

        // Two saves: parent (deactivated) + new (active v2).
        ArgumentCaptor<AdmissionOrderSet> captor = ArgumentCaptor.forClass(AdmissionOrderSet.class);
        verify(orderSetRepo, times(2)).save(captor.capture());
        List<AdmissionOrderSet> saved = captor.getAllValues();

        AdmissionOrderSet frozenParent = saved.get(0);
        assertThat(frozenParent.getId()).isEqualTo(ORDER_SET_ID);
        assertThat(frozenParent.getActive()).isFalse();

        AdmissionOrderSet newVersion = saved.get(1);
        assertThat(newVersion.getVersion()).isEqualTo(2);
        assertThat(newVersion.getActive()).isTrue();
        assertThat(newVersion.getParentOrderSet()).isSameAs(frozenParent);
    }

    @Test
    void deactivateMarksRowInactiveWithReasonAndActor() {
        when(orderSetRepo.findById(ORDER_SET_ID)).thenReturn(Optional.of(existing()));
        when(staffRepo.findById(STAFF_ID)).thenReturn(Optional.of(staff()));
        when(orderSetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AdmissionOrderSetResponseDTO dto = service.deactivate(ORDER_SET_ID, "superseded", STAFF_ID);
        assertThat(dto.getActive()).isFalse();
        assertThat(dto.getDeactivationReason()).isEqualTo("superseded");
    }

    @Test
    void applyToAdmissionFansOutEachItem() {
        AdmissionOrderSet os = existing();
        os.setOrderItems(List.of(
            Map.of("orderType", "LAB", "orderName", "Lactate"),
            Map.of("orderType", "MEDICATION", "medicationName", "Ceftriaxone"),
            Map.of("orderType", "IMAGING", "modality", "CT", "studyType", "CT abdo")
        ));
        when(orderSetRepo.findById(ORDER_SET_ID)).thenReturn(Optional.of(os));

        Admission admission = new Admission();
        admission.setId(ADMISSION_ID);
        admission.setHospital(hospital());
        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        admission.setPatient(p);
        admission.setPrimaryDiagnosisCode("A41.9");
        when(admissionRepo.findById(ADMISSION_ID)).thenReturn(Optional.of(admission));
        when(admissionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID labId = UUID.randomUUID(), medId = UUID.randomUUID(), imgId = UUID.randomUUID();
        when(dispatcher.dispatch(any(), any(OrderSetApplicationContext.class), any(Locale.class)))
            .thenReturn(DispatchResult.lab(labId))
            .thenReturn(DispatchResult.medication(medId, List.of()))
            .thenReturn(DispatchResult.imaging(imgId));

        ApplyOrderSetRequestDTO req = new ApplyOrderSetRequestDTO(
            UUID.randomUUID(), STAFF_ID, false
        );
        AppliedOrderSetSummaryDTO summary = service.applyToAdmission(
            ADMISSION_ID, ORDER_SET_ID, req, Locale.ENGLISH
        );

        assertThat(summary.totalCreated()).isEqualTo(3);
        assertThat(summary.labOrderIds()).containsExactly(labId);
        assertThat(summary.prescriptionIds()).containsExactly(medId);
        assertThat(summary.imagingOrderIds()).containsExactly(imgId);
        assertThat(summary.skippedItemCount()).isZero();
    }

    @Test
    void applyRefusesDeactivatedOrderSet() {
        AdmissionOrderSet os = existing();
        os.setActive(false);
        when(orderSetRepo.findById(ORDER_SET_ID)).thenReturn(Optional.of(os));

        ApplyOrderSetRequestDTO req = new ApplyOrderSetRequestDTO(UUID.randomUUID(), STAFF_ID, false);
        assertThatThrownBy(() ->
            service.applyToAdmission(ADMISSION_ID, ORDER_SET_ID, req, Locale.ENGLISH))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listSearchesByNameWhenSearchProvided() {
        AdmissionOrderSet os = existing();
        when(orderSetRepo.searchByName(HOSPITAL_ID, "sepsis")).thenReturn(List.of(os));

        var page = service.list(HOSPITAL_ID, "sepsis", PageRequest.of(0, 20));
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getName()).isEqualTo("Sepsis bundle");
    }
}
