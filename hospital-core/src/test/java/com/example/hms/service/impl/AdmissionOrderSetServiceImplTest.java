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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdmissionOrderSetServiceImplTest {

    private final AdmissionOrderSetRepository orderSetRepo = mock(AdmissionOrderSetRepository.class);
    private final AdmissionRepository admissionRepo = mock(AdmissionRepository.class);
    private final HospitalRepository hospitalRepo = mock(HospitalRepository.class);
    private final DepartmentRepository departmentRepo = mock(DepartmentRepository.class);
    private final StaffRepository staffRepo = mock(StaffRepository.class);
    private final AdmissionOrderSetMapper mapper = new AdmissionOrderSetMapper();
    private final OrderSetItemDispatcher dispatcher = mock(OrderSetItemDispatcher.class);

    private final com.example.hms.repository.MedicationCatalogItemRepository catalogRepo =
        mock(com.example.hms.repository.MedicationCatalogItemRepository.class);

    private AdmissionOrderSetServiceImpl service;

    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID STAFF_ID = UUID.randomUUID();
    private static final UUID ADMISSION_ID = UUID.randomUUID();
    private static final UUID ORDER_SET_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdmissionOrderSetServiceImpl(
            orderSetRepo, admissionRepo, hospitalRepo, departmentRepo, staffRepo,
            mapper, dispatcher, new com.example.hms.service.orderset.OrderSetItemQuantityRules(catalogRepo)
        );
        when(catalogRepo.findByHospital_IdAndActiveTrue(any(), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(morphine())));
    }

    private static com.example.hms.model.medication.MedicationCatalogItem morphine() {
        com.example.hms.model.medication.MedicationCatalogItem item =
            new com.example.hms.model.medication.MedicationCatalogItem();
        item.setId(UUID.randomUUID());
        item.setNameFr("Morphine");
        item.setCode("MOR");
        item.setControlled(true);
        return item;
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
        when(hospitalRepo.findClinicalById(HOSPITAL_ID)).thenReturn(Optional.of(hospital()));
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

    /**
     * QA D1226 class (round 24): the deactivation recorded whatever staff id
     * the client sent (any staff, any hospital). The actor is the session's
     * staff record at the order set's hospital; a named colleague is ignored.
     */
    @Test
    void deactivateRecordsTheSessionNotTheNamedStaff() {
        AdmissionOrderSet orderSet = existing();
        UUID callerUserId = UUID.randomUUID();
        com.example.hms.model.User caller = new com.example.hms.model.User();
        caller.setId(callerUserId);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(caller, null, List.of()));
        try {
            Staff me = new Staff();
            me.setId(UUID.randomUUID());
            when(orderSetRepo.findById(ORDER_SET_ID)).thenReturn(Optional.of(orderSet));
            when(staffRepo.findByUserIdAndHospitalId(callerUserId, HOSPITAL_ID)).thenReturn(Optional.of(me));
            when(orderSetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.deactivate(ORDER_SET_ID, "superseded", STAFF_ID);

            assertThat(orderSet.getLastModifiedBy()).isSameAs(me);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
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

    /**
     * QA D1382 (round 32): an order set accepted a PRN medication item with no
     * interval (201) and the apply then skipped it silently. Every save (a new
     * set and every new version) now holds each MEDICATION item to the
     * prescription rule, named on the item's own field; nothing is saved.
     */
    @Test
    void d1382_aPrnItemBreakingThePrescriptionRuleIsRefusedAtSave() {
        when(hospitalRepo.findClinicalById(HOSPITAL_ID)).thenReturn(Optional.of(hospital()));
        when(staffRepo.findById(STAFF_ID)).thenReturn(Optional.of(staff()));
        when(orderSetRepo.findById(ORDER_SET_ID)).thenReturn(Optional.of(existing()));
        Map<String, Map<String, Object>> refusedOn = new java.util.LinkedHashMap<>();
        refusedOn.put("orderItems[1].prnMinIntervalHours",
            Map.of("orderType", "MEDICATION", "medicationName", "Paracetamol 1 g", "frequency", "au besoin",
                "asNeeded", true));
        refusedOn.put("orderItems[1].asNeeded",
            Map.of("orderType", "MEDICATION", "medicationName", "Paracetamol 1 g", "frequency", "sur demande"));
        refusedOn.put("orderItems[1].prnMinIntervalHours ",
            Map.of("orderType", "medication", "medicationName", "Paracetamol 1 g", "frequency", "SOS",
                "asNeeded", true, "prnMinIntervalHours", 2.5));
        for (Map.Entry<String, Map<String, Object>> e : refusedOn.entrySet()) {
            AdmissionOrderSetRequestDTO request = sampleRequest();
            request.setOrderItems(List.of(Map.of("orderType", "LAB", "orderName", "Lactate"), e.getValue()));
            assertThatThrownBy(() -> service.create(request)).as(e.getKey())
                .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                    ex -> assertThat(ex.getField()).isEqualTo(e.getKey().strip()));
            assertThatThrownBy(() -> service.update(ORDER_SET_ID, request)).as(e.getKey())
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class);
        }
        verify(orderSetRepo, never()).save(any());

        when(orderSetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AdmissionOrderSetRequestDTO ok = sampleRequest();
        ok.setOrderItems(List.of(Map.of("orderType", "MEDICATION", "medicationName", "Paracetamol 1 g",
            "frequency", "SOS", "asNeeded", true, "prnMinIntervalHours", 4)));
        assertThat(service.create(ok).getVersion()).isEqualTo(1);
    }

    /**
     * QA D1382 (round 32): a set saved before the item rule, with a PRN item
     * that has no interval, is refused by name at apply before any order is
     * created - never applied with the item silently skipped.
     */
    @Test
    void d1382_aStoredPrnItemWithoutAnIntervalIsRefusedAtApplyNeverSkipped() {
        AdmissionOrderSet os = existing();
        os.setOrderItems(List.of(
            Map.of("orderType", "LAB", "orderName", "Lactate"),
            Map.of("orderType", "MEDICATION", "medicationName", "Paracetamol 1 g", "frequency", "si douleur",
                "asNeeded", true)));
        when(orderSetRepo.findById(ORDER_SET_ID)).thenReturn(Optional.of(os));
        Admission admission = new Admission();
        admission.setId(ADMISSION_ID);
        admission.setHospital(hospital());
        when(admissionRepo.findById(ADMISSION_ID)).thenReturn(Optional.of(admission));

        ApplyOrderSetRequestDTO req = new ApplyOrderSetRequestDTO(UUID.randomUUID(), STAFF_ID, false);
        assertThatThrownBy(() -> service.applyToAdmission(ADMISSION_ID, ORDER_SET_ID, req, Locale.ENGLISH))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                ex -> assertThat(ex.getField()).isEqualTo("orderItems[1].prnMinIntervalHours"));
        verifyNoInteractions(dispatcher);
        verify(admissionRepo, never()).save(any());
    }

    /**
     * QA D1396 (round 33): a set with a catalogue-controlled drug is refused at
     * save without its quantity, by name, on the item's own field; with it, it
     * saves. Non-controlled drugs are not asked.
     */
    @Test
    void d1396_aControlledItemStatesItsQuantityAtSave() {
        when(hospitalRepo.findClinicalById(HOSPITAL_ID)).thenReturn(Optional.of(hospital()));
        when(staffRepo.findById(STAFF_ID)).thenReturn(Optional.of(staff()));
        when(orderSetRepo.findById(ORDER_SET_ID)).thenReturn(Optional.of(existing()));
        AdmissionOrderSetRequestDTO refused = sampleRequest();
        refused.setOrderItems(List.of(Map.of("orderType", "LAB", "orderName", "Lactate"),
            Map.of("orderType", "MEDICATION", "medicationName", "Morphine", "dose", "2 mg", "frequency", "Q4H")));
        assertThatThrownBy(() -> service.create(refused))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                ex -> assertThat(ex.getField()).isEqualTo("orderItems[1].quantity"));
        assertThatThrownBy(() -> service.update(ORDER_SET_ID, refused))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                ex -> assertThat(ex.getField()).isEqualTo("orderItems[1].quantity"));
        verify(orderSetRepo, never()).save(any());

        when(orderSetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AdmissionOrderSetRequestDTO ok = sampleRequest();
        ok.setOrderItems(List.of(Map.of("orderType", "MEDICATION", "medicationName", "Morphine", "dose", "2 mg",
            "frequency", "Q4H", "quantity", 6, "quantityUnit", "ampoules")));
        assertThat(service.create(ok).getVersion()).isEqualTo(1);
    }

    /** QA D1396: a set saved before the rule is refused at apply, clearly, before any order is created. */
    @Test
    void d1396_aStoredControlledItemWithoutAQuantityIsRefusedAtApplyNeverSkipped() {
        AdmissionOrderSet os = existing();
        os.setOrderItems(List.of(
            Map.of("orderType", "LAB", "orderName", "Lactate"),
            Map.of("orderType", "MEDICATION", "medicationName", "Morphine", "dose", "2 mg", "frequency", "Q4H")));
        when(orderSetRepo.findById(ORDER_SET_ID)).thenReturn(Optional.of(os));
        Admission admission = new Admission();
        admission.setId(ADMISSION_ID);
        admission.setHospital(hospital());
        when(admissionRepo.findById(ADMISSION_ID)).thenReturn(Optional.of(admission));

        ApplyOrderSetRequestDTO req = new ApplyOrderSetRequestDTO(UUID.randomUUID(), STAFF_ID, false);
        assertThatThrownBy(() -> service.applyToAdmission(ADMISSION_ID, ORDER_SET_ID, req, Locale.ENGLISH))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                ex -> assertThat(ex.getField()).isEqualTo("orderItems[1].quantity"));
        verifyNoInteractions(dispatcher);
        verify(admissionRepo, never()).save(any());
    }

    /** QA D1395: an item's wording is held to the rule at save, and D1391: a fixed schedule is not. */
    @Test
    void d1395_anItemWordingIsHeldToTheFixedScheduleRule() {
        when(hospitalRepo.findClinicalById(HOSPITAL_ID)).thenReturn(Optional.of(hospital()));
        when(staffRepo.findById(STAFF_ID)).thenReturn(Optional.of(staff()));
        when(orderSetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        for (String refused : List.of("pour douleur", "max 4/j", "selon prescription m\u00e9dicale", "SP + AQ")) {
            AdmissionOrderSetRequestDTO request = sampleRequest();
            request.setOrderItems(List.of(Map.of("orderType", "MEDICATION", "medicationName", "Paracetamol",
                "frequency", refused)));
            assertThatThrownBy(() -> service.create(request)).as(refused)
                .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                    ex -> assertThat(ex.getField()).isEqualTo("orderItems[0].asNeeded"));
        }
        for (String accepted : List.of("Q6H", "TPI-SP", "3 fois par jour selon ordonnance", "Prise unique (TPI-SP 2)")) {
            AdmissionOrderSetRequestDTO request = sampleRequest();
            request.setOrderItems(List.of(Map.of("orderType", "MEDICATION", "medicationName", "Paracetamol",
                "frequency", accepted)));
            assertThat(service.create(request).getVersion()).as(accepted).isEqualTo(1);
        }
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

    /** QA D1428: a closed stay takes no new orders, before any item is read. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = com.example.hms.enums.AdmissionStatus.class,
        names = {"DISCHARGED", "CANCELLED", "TRANSFERRED", "DECEASED"})
    void qaD1428_aClosedStayTakesNoOrderSet(com.example.hms.enums.AdmissionStatus closed) {
        AdmissionOrderSet os = existing();
        when(orderSetRepo.findById(ORDER_SET_ID)).thenReturn(Optional.of(os));
        Admission admission = new Admission();
        admission.setId(ADMISSION_ID);
        admission.setHospital(hospital());
        admission.setStatus(closed);
        when(admissionRepo.findById(ADMISSION_ID)).thenReturn(Optional.of(admission));

        ApplyOrderSetRequestDTO req = new ApplyOrderSetRequestDTO(UUID.randomUUID(), STAFF_ID, false);
        assertThatThrownBy(() -> service.applyToAdmission(ADMISSION_ID, ORDER_SET_ID, req, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.ConflictException.class);
        verifyNoInteractions(dispatcher);
    }
}
