package com.example.hms.service.impl;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.MicroCultureStatus;
import com.example.hms.enums.MicroGrowthResult;
import com.example.hms.enums.MicroSusceptibilityInterpretation;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.MicroCultureResult;
import com.example.hms.model.MicroIsolate;
import com.example.hms.model.MicroSusceptibility;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.MicroCultureRequestDTO;
import com.example.hms.payload.dto.MicroCultureResponseDTO;
import com.example.hms.payload.dto.MicroCultureUpdateDTO;
import com.example.hms.payload.dto.MicroIsolateRequestDTO;
import com.example.hms.payload.dto.MicroSusceptibilityRequestDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.repository.MicroCultureResultRepository;
import com.example.hms.repository.MicroIsolateRepository;
import com.example.hms.repository.MicroSusceptibilityRepository;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Microbiology culture reports (P3 #19): tenancy is 404-not-403 on reads AND
 * writes, the PRELIMINARY→FINAL→CORRECTED lifecycle never silently unlocks,
 * and a finalized growth report notifies the ordering provider.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MicroCultureServiceImplTest {

    @Mock private MicroCultureResultRepository cultureRepository;
    @Mock private MicroIsolateRepository isolateRepository;
    @Mock private MicroSusceptibilityRepository susceptibilityRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabSpecimenRepository specimenRepository;
    @Mock private PatientChartAccess patientChartAccess;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;

    private MicroCultureServiceImpl service;

    private UUID hospitalId;
    private UUID orderId;
    private UUID cultureId;
    private Hospital hospital;
    private Patient patient;
    private LabOrder order;
    private MicroCultureResult culture;

    @BeforeEach
    void setUp() {
        service = new MicroCultureServiceImpl(
            cultureRepository, isolateRepository, susceptibilityRepository,
            labOrderRepository, specimenRepository, patientChartAccess,
            staffRepository, userRepository, notificationService);

        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);

        patient = Patient.builder()
            .firstName("Awa").lastName("Kaboré")
            .dateOfBirth(LocalDate.of(1990, 5, 1))
            .build();
        patient.setId(UUID.randomUUID());
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setHospital(hospital);
        registration.setActive(true);
        patient.setHospitalRegistrations(Set.of(registration));

        User orderingUser = new User();
        orderingUser.setUsername("dr.traore");
        Staff orderingStaff = Staff.builder().user(orderingUser).build();

        orderId = UUID.randomUUID();
        order = LabOrder.builder()
            .hospital(hospital)
            .patient(patient)
            .orderingStaff(orderingStaff)
            .status(LabOrderStatus.IN_PROGRESS)
            .build();
        order.setId(orderId);

        cultureId = UUID.randomUUID();
        culture = MicroCultureResult.builder()
            .labOrder(order)
            .patient(patient)
            .hospital(hospital)
            .status(MicroCultureStatus.PRELIMINARY)
            .build();
        culture.setId(cultureId);

        when(labOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(cultureRepository.findById(cultureId)).thenReturn(Optional.of(culture));
        when(cultureRepository.save(any(MicroCultureResult.class))).thenAnswer(i -> i.getArgument(0));
        when(isolateRepository.save(any(MicroIsolate.class))).thenAnswer(i -> i.getArgument(0));
        when(susceptibilityRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(isolateRepository.findByCultureResult_IdOrderByIsolateNumberAscCreatedAtAsc(any()))
            .thenReturn(List.of());
        when(susceptibilityRepository.findByIsolate_IdInOrderByAntibioticNameAsc(any()))
            .thenReturn(List.of());
        when(isolateRepository.countByCultureResult_Id(any())).thenReturn(0L);
        when(staffRepository.findByUserIdAndHospitalId(any(), any())).thenReturn(Optional.empty());
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        when(patientChartAccess.require(eq(patient.getId()), any())).thenReturn(patient);
    }

    private MicroIsolate storedIsolate() {
        MicroIsolate isolate = MicroIsolate.builder()
            .cultureResult(culture)
            .isolateNumber(1)
            .organismName("Escherichia coli")
            .build();
        isolate.setId(UUID.randomUUID());
        return isolate;
    }

    /* ── create ────────────────────────────────────────────────────────── */

    @Test
    void createAttachesTheCultureToTheOrder() {
        MicroCultureRequestDTO request = MicroCultureRequestDTO.builder()
            .labOrderId(orderId)
            .specimenSource("Blood — peripheral")
            .build();

        MicroCultureResponseDTO created = service.createCulture(hospitalId, null, request);

        assertThat(created.getLabOrderId()).isEqualTo(orderId);
        assertThat(created.getStatus()).isEqualTo(MicroCultureStatus.PRELIMINARY);
        assertThat(created.getPatientId()).isEqualTo(patient.getId());
    }

    @Test
    void createRefusesWithoutHospitalScope() {
        MicroCultureRequestDTO request = MicroCultureRequestDTO.builder().labOrderId(orderId).build();

        assertThatThrownBy(() -> service.createCulture(null, null, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital");
    }

    @Test
    void createReturns404ForAForeignHospitalsOrder() {
        UUID foreignScope = UUID.randomUUID();
        MicroCultureRequestDTO request = MicroCultureRequestDTO.builder().labOrderId(orderId).build();

        // 404-not-403: the message must not reveal the order exists elsewhere.
        assertThatThrownBy(() -> service.createCulture(foreignScope, null, request))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Lab order not found");
    }

    @Test
    void createRefusesACancelledOrder() {
        order.setStatus(LabOrderStatus.CANCELLED);
        MicroCultureRequestDTO request = MicroCultureRequestDTO.builder().labOrderId(orderId).build();

        assertThatThrownBy(() -> service.createCulture(hospitalId, null, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cancelled");
    }

    /* ── lifecycle ─────────────────────────────────────────────────────── */

    @Test
    void finalizeRequiresAGrowthResult() {
        assertThatThrownBy(() -> service.finalizeCulture(cultureId, hospitalId, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("growth result is required");
    }

    @Test
    void finalizeOfAGrowthReportRequiresAnIsolate() {
        culture.setGrowthResult(MicroGrowthResult.GROWTH);
        when(isolateRepository.countByCultureResult_Id(cultureId)).thenReturn(0L);

        assertThatThrownBy(() -> service.finalizeCulture(cultureId, hospitalId, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("at least one isolate");
    }

    @Test
    void finalizeStampsTheReportFinal() {
        culture.setGrowthResult(MicroGrowthResult.NO_GROWTH);
        UUID actor = UUID.randomUUID();

        MicroCultureResponseDTO finalized = service.finalizeCulture(cultureId, hospitalId, actor);

        assertThat(finalized.getStatus()).isEqualTo(MicroCultureStatus.FINAL);
        assertThat(culture.getFinalizedAt()).isNotNull();
        assertThat(culture.getFinalizedByUserId()).isEqualTo(actor);
        // No growth — nobody is paged about a negative.
        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
    }

    @Test
    void finalizeRefusesAnAlreadyFinalReport() {
        culture.setStatus(MicroCultureStatus.FINAL);

        assertThatThrownBy(() -> service.finalizeCulture(cultureId, hospitalId, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already final");
    }

    @Test
    void finalizedGrowthNotifiesTheOrderingProvider() {
        culture.setGrowthResult(MicroGrowthResult.GROWTH);
        MicroIsolate isolate = storedIsolate();
        when(isolateRepository.countByCultureResult_Id(cultureId)).thenReturn(1L);
        when(isolateRepository.findByCultureResult_IdOrderByIsolateNumberAscCreatedAtAsc(cultureId))
            .thenReturn(List.of(isolate));

        service.finalizeCulture(cultureId, hospitalId, null);

        verify(notificationService).createNotification(
            org.mockito.ArgumentMatchers.contains("Escherichia coli"),
            eq("dr.traore"),
            eq("POSITIVE_CULTURE_RESULT"));
    }

    @Test
    void mutationAfterFinalDemandsACorrectionReason() {
        culture.setStatus(MicroCultureStatus.FINAL);
        MicroCultureUpdateDTO update = MicroCultureUpdateDTO.builder().gramStain("GPC in clusters").build();

        assertThatThrownBy(() -> service.updateCulture(cultureId, hospitalId, update))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("correction reason");
    }

    @Test
    void correctionWithAReasonMarksTheReportCorrected() {
        culture.setStatus(MicroCultureStatus.FINAL);
        MicroCultureUpdateDTO update = MicroCultureUpdateDTO.builder()
            .gramStain("GPC in clusters")
            .correctionReason("Stain re-read by senior scientist")
            .build();

        MicroCultureResponseDTO corrected = service.updateCulture(cultureId, hospitalId, update);

        assertThat(corrected.getStatus()).isEqualTo(MicroCultureStatus.CORRECTED);
        assertThat(culture.getCorrectedAt()).isNotNull();
        assertThat(culture.getCorrectionReason()).isEqualTo("Stain re-read by senior scientist");
    }

    /* ── isolates + susceptibilities ───────────────────────────────────── */

    @Test
    void isolateOnANoGrowthReportIsRefused() {
        culture.setGrowthResult(MicroGrowthResult.NO_GROWTH);
        MicroIsolateRequestDTO request = MicroIsolateRequestDTO.builder()
            .organismName("Escherichia coli").build();

        assertThatThrownBy(() -> service.addIsolate(cultureId, hospitalId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("no-growth");
    }

    @Test
    void duplicateAntibioticOnAnIsolateIsRefused() {
        MicroIsolate isolate = storedIsolate();
        when(isolateRepository.findById(isolate.getId())).thenReturn(Optional.of(isolate));
        when(susceptibilityRepository.existsByIsolate_IdAndAntibioticNameIgnoreCase(
            isolate.getId(), "Amoxicillin")).thenReturn(true);
        MicroSusceptibilityRequestDTO request = MicroSusceptibilityRequestDTO.builder()
            .antibioticName("Amoxicillin")
            .interpretation(MicroSusceptibilityInterpretation.RESISTANT)
            .build();
        UUID isolateId = isolate.getId();

        assertThatThrownBy(() -> service.addSusceptibility(cultureId, isolateId, hospitalId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already recorded");
    }

    @Test
    void isolateOfAnotherCultureIs404() {
        MicroIsolate foreign = MicroIsolate.builder()
            .cultureResult(MicroCultureResult.builder().build())
            .organismName("Klebsiella pneumoniae")
            .build();
        foreign.getCultureResult().setId(UUID.randomUUID());
        foreign.setId(UUID.randomUUID());
        when(isolateRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        MicroIsolateRequestDTO request = MicroIsolateRequestDTO.builder()
            .organismName("Klebsiella pneumoniae").build();
        UUID foreignIsolateId = foreign.getId();

        assertThatThrownBy(() -> service.updateIsolate(cultureId, foreignIsolateId, hospitalId, request))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Isolate not found");
    }

    /* ── reads ─────────────────────────────────────────────────────────── */

    @Test
    void cultureReadIs404ForAForeignScope() {
        UUID foreignScope = UUID.randomUUID();

        assertThatThrownBy(() -> service.getCulture(cultureId, foreignScope))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Culture report not found");
    }

    @Test
    void patientListIs404WhenTheScopedCallerHasNoRegistration() {
        UUID foreignScope = UUID.randomUUID();
        UUID patientId = patient.getId();
        // The scope decision lives in PatientChartAccess (tested there, including
        // that unknown and unregistered are indistinguishable); this asserts the
        // culture list propagates it instead of returning an empty list.
        when(patientChartAccess.require(patientId, foreignScope))
            .thenThrow(new ResourceNotFoundException("patient.notFound", patientId));

        assertThatThrownBy(() -> service.getForPatient(patientId, foreignScope))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void hospitalListRequiresScope() {
        assertThatThrownBy(() -> service.getForHospital(null, null,
            org.springframework.data.domain.PageRequest.of(0, 20)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital");
    }

    /* ── happy paths (the guards above are only half the service) ──────── */

    @Test
    void createLinksTheSpecimenWhenItBelongsToTheOrder() {
        com.example.hms.model.LabSpecimen specimen = com.example.hms.model.LabSpecimen.builder()
            .labOrder(order)
            .accessionNumber("ACC-20260822-00001")
            .build();
        specimen.setId(UUID.randomUUID());
        when(specimenRepository.findById(specimen.getId())).thenReturn(Optional.of(specimen));
        MicroCultureRequestDTO request = MicroCultureRequestDTO.builder()
            .labOrderId(orderId)
            .specimenId(specimen.getId())
            .build();

        MicroCultureResponseDTO created = service.createCulture(hospitalId, null, request);

        assertThat(created.getSpecimenId()).isEqualTo(specimen.getId());
        assertThat(created.getSpecimenAccessionNumber()).isEqualTo("ACC-20260822-00001");
    }

    @Test
    void createRefusesASpecimenFromAnotherOrder() {
        LabOrder otherOrder = LabOrder.builder().hospital(hospital).patient(patient).build();
        otherOrder.setId(UUID.randomUUID());
        com.example.hms.model.LabSpecimen specimen = com.example.hms.model.LabSpecimen.builder()
            .labOrder(otherOrder)
            .build();
        specimen.setId(UUID.randomUUID());
        when(specimenRepository.findById(specimen.getId())).thenReturn(Optional.of(specimen));
        MicroCultureRequestDTO request = MicroCultureRequestDTO.builder()
            .labOrderId(orderId)
            .specimenId(specimen.getId())
            .build();

        assertThatThrownBy(() -> service.createCulture(hospitalId, null, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("different lab order");
    }

    @Test
    void addIsolateNumbersItselfAfterTheExistingOnes() {
        culture.setGrowthResult(MicroGrowthResult.GROWTH);
        when(isolateRepository.countByCultureResult_Id(cultureId)).thenReturn(2L);
        MicroIsolateRequestDTO request = MicroIsolateRequestDTO.builder()
            .organismName("  Staphylococcus aureus  ")
            .growthQuantity("moderate")
            .build();

        service.addIsolate(cultureId, hospitalId, request);

        org.mockito.ArgumentCaptor<MicroIsolate> captor =
            org.mockito.ArgumentCaptor.forClass(MicroIsolate.class);
        verify(isolateRepository).save(captor.capture());
        assertThat(captor.getValue().getIsolateNumber()).isEqualTo(3);
        assertThat(captor.getValue().getOrganismName()).isEqualTo("Staphylococcus aureus");
        assertThat(captor.getValue().getGrowthQuantity()).isEqualTo("moderate");
    }

    @Test
    void updateIsolateRewritesItsFields() {
        MicroIsolate isolate = storedIsolate();
        when(isolateRepository.findById(isolate.getId())).thenReturn(Optional.of(isolate));
        MicroIsolateRequestDTO request = MicroIsolateRequestDTO.builder()
            .organismName("Escherichia coli (ESBL)")
            .organismCode("ESBL-EC")
            .isolateNumber(2)
            .build();

        service.updateIsolate(cultureId, isolate.getId(), hospitalId, request);

        assertThat(isolate.getOrganismName()).isEqualTo("Escherichia coli (ESBL)");
        assertThat(isolate.getOrganismCode()).isEqualTo("ESBL-EC");
        assertThat(isolate.getIsolateNumber()).isEqualTo(2);
    }

    @Test
    void deleteIsolateRemovesItsSusceptibilityRowsExplicitly() {
        MicroIsolate isolate = storedIsolate();
        when(isolateRepository.findById(isolate.getId())).thenReturn(Optional.of(isolate));
        MicroSusceptibility row = MicroSusceptibility.builder()
            .isolate(isolate)
            .antibioticName("Amoxicillin")
            .interpretation(MicroSusceptibilityInterpretation.RESISTANT)
            .build();
        row.setId(UUID.randomUUID());
        when(susceptibilityRepository.findByIsolate_IdInOrderByAntibioticNameAsc(List.of(isolate.getId())))
            .thenReturn(List.of(row));

        service.deleteIsolate(cultureId, isolate.getId(), hospitalId, null);

        // The H2 test schema carries no ON DELETE CASCADE, so the service
        // must delete the children itself.
        verify(susceptibilityRepository).deleteAll(List.of(row));
        verify(isolateRepository).delete(isolate);
    }

    @Test
    void addSusceptibilityStoresTheRow() {
        MicroIsolate isolate = storedIsolate();
        when(isolateRepository.findById(isolate.getId())).thenReturn(Optional.of(isolate));
        MicroSusceptibilityRequestDTO request = MicroSusceptibilityRequestDTO.builder()
            .antibioticName(" Ciprofloxacin ")
            .method(com.example.hms.enums.MicroSusceptibilityMethod.MIC)
            .micValue("<=0.25")
            .interpretation(MicroSusceptibilityInterpretation.SUSCEPTIBLE)
            .build();

        service.addSusceptibility(cultureId, isolate.getId(), hospitalId, request);

        org.mockito.ArgumentCaptor<MicroSusceptibility> captor =
            org.mockito.ArgumentCaptor.forClass(MicroSusceptibility.class);
        verify(susceptibilityRepository).save(captor.capture());
        assertThat(captor.getValue().getAntibioticName()).isEqualTo("Ciprofloxacin");
        assertThat(captor.getValue().getMicValue()).isEqualTo("<=0.25");
        assertThat(captor.getValue().getInterpretation())
            .isEqualTo(MicroSusceptibilityInterpretation.SUSCEPTIBLE);
    }

    @Test
    void deleteSusceptibilityIs404ForARowOfAnotherIsolate() {
        MicroIsolate isolate = storedIsolate();
        when(isolateRepository.findById(isolate.getId())).thenReturn(Optional.of(isolate));
        MicroIsolate otherIsolate = storedIsolate();
        MicroSusceptibility foreignRow = MicroSusceptibility.builder()
            .isolate(otherIsolate)
            .antibioticName("Amoxicillin")
            .interpretation(MicroSusceptibilityInterpretation.RESISTANT)
            .build();
        foreignRow.setId(UUID.randomUUID());
        when(susceptibilityRepository.findById(foreignRow.getId())).thenReturn(Optional.of(foreignRow));
        UUID isolateId = isolate.getId();
        UUID rowId = foreignRow.getId();

        assertThatThrownBy(() -> service.deleteSusceptibility(cultureId, isolateId, rowId, hospitalId, null))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Susceptibility not found");
    }

    @Test
    void deleteSusceptibilityRemovesTheRow() {
        MicroIsolate isolate = storedIsolate();
        when(isolateRepository.findById(isolate.getId())).thenReturn(Optional.of(isolate));
        MicroSusceptibility row = MicroSusceptibility.builder()
            .isolate(isolate)
            .antibioticName("Amoxicillin")
            .interpretation(MicroSusceptibilityInterpretation.RESISTANT)
            .build();
        row.setId(UUID.randomUUID());
        when(susceptibilityRepository.findById(row.getId())).thenReturn(Optional.of(row));

        service.deleteSusceptibility(cultureId, isolate.getId(), row.getId(), hospitalId, null);

        verify(susceptibilityRepository).delete(row);
    }

    @Test
    void dtoAssemblesTheFullReportTree() {
        order.setLabTestDefinition(com.example.hms.model.LabTestDefinition.builder()
            .name("Blood Culture").build());
        hospital.setName("CHU Ouaga");
        User reporterUser = new User();
        reporterUser.setFirstName("Fatou");
        reporterUser.setLastName("Zongo");
        Staff reporter = Staff.builder().user(reporterUser).build();
        culture.setReportedByStaff(reporter);
        UUID finalizerId = UUID.randomUUID();
        User finalizer = new User();
        finalizer.setFirstName("Ali");
        finalizer.setLastName("Sanou");
        when(userRepository.findById(finalizerId)).thenReturn(Optional.of(finalizer));
        culture.setFinalizedByUserId(finalizerId);

        MicroIsolate isolate = storedIsolate();
        MicroSusceptibility row = MicroSusceptibility.builder()
            .isolate(isolate)
            .antibioticName("Ciprofloxacin")
            .interpretation(MicroSusceptibilityInterpretation.SUSCEPTIBLE)
            .build();
        row.setId(UUID.randomUUID());
        when(isolateRepository.findByCultureResult_IdOrderByIsolateNumberAscCreatedAtAsc(cultureId))
            .thenReturn(List.of(isolate));
        when(susceptibilityRepository.findByIsolate_IdInOrderByAntibioticNameAsc(List.of(isolate.getId())))
            .thenReturn(List.of(row));

        MicroCultureResponseDTO dto = service.getCulture(cultureId, hospitalId);

        assertThat(dto.getLabTestName()).isEqualTo("Blood Culture");
        assertThat(dto.getHospitalName()).isEqualTo("CHU Ouaga");
        assertThat(dto.getReportedByName()).isEqualTo("Fatou Zongo");
        assertThat(dto.getFinalizedByName()).isEqualTo("Ali Sanou");
        assertThat(dto.getIsolates()).hasSize(1);
        assertThat(dto.getIsolates().get(0).getOrganismName()).isEqualTo("Escherichia coli");
        assertThat(dto.getIsolates().get(0).getSusceptibilities()).hasSize(1);
        assertThat(dto.getIsolates().get(0).getSusceptibilities().get(0).getAntibioticName())
            .isEqualTo("Ciprofloxacin");
    }

    @Test
    void hospitalListMapsThePage() {
        when(cultureRepository.findForHospital(eq(hospitalId), eq(null), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(culture)));

        org.springframework.data.domain.Page<MicroCultureResponseDTO> page =
            service.getForHospital(hospitalId, null, org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(cultureId);
    }

    @Test
    void patientListReturnsNewestFirstFromTheRepository() {
        when(cultureRepository.findForPatient(patient.getId(), hospitalId)).thenReturn(List.of(culture));

        List<MicroCultureResponseDTO> list = service.getForPatient(patient.getId(), hospitalId);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getId()).isEqualTo(cultureId);
    }

    @Test
    void preliminaryUpdateAppliesFieldsWithoutACorrectionStamp() {
        MicroCultureUpdateDTO update = MicroCultureUpdateDTO.builder()
            .specimenSource("Urine — midstream")
            .gramStain("GNR")
            .growthResult(MicroGrowthResult.GROWTH)
            .notes("Repeat requested")
            .collectedAt(java.time.LocalDateTime.now().minusHours(2))
            .build();

        MicroCultureResponseDTO updated = service.updateCulture(cultureId, hospitalId, update);

        assertThat(updated.getStatus()).isEqualTo(MicroCultureStatus.PRELIMINARY);
        assertThat(culture.getSpecimenSource()).isEqualTo("Urine — midstream");
        assertThat(culture.getGramStain()).isEqualTo("GNR");
        assertThat(culture.getGrowthResult()).isEqualTo(MicroGrowthResult.GROWTH);
        assertThat(culture.getCorrectedAt()).isNull();
    }

    @Test
    void markingNoGrowthWhileIsolatesExistIsRefused() {
        when(isolateRepository.countByCultureResult_Id(cultureId)).thenReturn(1L);
        MicroCultureUpdateDTO update = MicroCultureUpdateDTO.builder()
            .growthResult(MicroGrowthResult.NO_GROWTH)
            .build();

        assertThatThrownBy(() -> service.updateCulture(cultureId, hospitalId, update))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Remove the isolates first");
    }

    @Test
    void positiveFinalizeWithNoResolvableOrderingUserSkipsNotificationQuietly() {
        order.setOrderingStaff(Staff.builder().build()); // staff row with no user account
        culture.setGrowthResult(MicroGrowthResult.GROWTH);
        when(isolateRepository.countByCultureResult_Id(cultureId)).thenReturn(1L);

        MicroCultureResponseDTO finalized = service.finalizeCulture(cultureId, hospitalId, null);

        // Best-effort by policy: the clinical write must survive a
        // notification dead end.
        assertThat(finalized.getStatus()).isEqualTo(MicroCultureStatus.FINAL);
        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
    }
}
