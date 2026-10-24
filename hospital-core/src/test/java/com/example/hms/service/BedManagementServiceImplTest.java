package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.BedStatus;
import com.example.hms.enums.WardType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Bed;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
class BedManagementServiceImplTest {

    @Mock private WardRepository wardRepository;
    @Mock private BedRepository bedRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private com.example.hms.repository.AdmissionRepository admissionRepository;
    @Mock private com.example.hms.repository.TransferOrderRepository transferOrderRepository;
    @Mock private com.example.hms.repository.PatientRepository patientRepository;

    @InjectMocks private BedManagementServiceImpl service;

    private UUID hospitalId;
    private Hospital hospital;
    private Ward ward;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);

        ward = Ward.builder().hospital(hospital).name("Maternity").code("MAT01")
            .wardType(WardType.MATERNITY).build();
        ward.setId(UUID.randomUUID());
    }

    /* ── Wards ─────────────────────────────────────────────────────────── */

    @Test
    void getWardsReturnsScopedWardsWithBedCounts() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(wardRepository.findByHospital_Id(hospitalId)).thenReturn(List.of(ward));
        when(bedRepository.countByHospitalGroupByWardIdAndStatus(hospitalId)).thenReturn(List.of(
            new Object[]{ward.getId(), BedStatus.AVAILABLE, 3L},
            new Object[]{ward.getId(), BedStatus.OCCUPIED, 2L}
        ));

        List<WardResponseDTO> wards = service.getWards(false);

        assertThat(wards).singleElement().satisfies(dto -> {
            assertThat(dto.getCode()).isEqualTo("MAT01");
            assertThat(dto.getTotalBeds()).isEqualTo(5);
            assertThat(dto.getAvailableBeds()).isEqualTo(3);
            assertThat(dto.getOccupiedBeds()).isEqualTo(2);
        });
    }

    @Test
    void getWardsHidesInactiveByDefault() {
        Ward inactive = Ward.builder().hospital(hospital).name("Old").code("OLD")
            .wardType(WardType.GENERAL).active(false).build();
        inactive.setId(UUID.randomUUID());
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(wardRepository.findByHospital_Id(hospitalId)).thenReturn(List.of(ward, inactive));
        when(bedRepository.countByHospitalGroupByWardIdAndStatus(hospitalId)).thenReturn(List.of());

        assertThat(service.getWards(false)).hasSize(1);
        assertThat(service.getWards(true)).hasSize(2);
    }

    @Test
    void createWardRejectsDuplicateCode() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(wardRepository.existsByHospital_IdAndCodeIgnoreCase(hospitalId, "MAT01")).thenReturn(true);

        WardRequestDTO request = WardRequestDTO.builder()
            .name("Maternity").code(" MAT01 ").wardType(WardType.MATERNITY).build();

        assertThatThrownBy(() -> service.createWard(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("MAT01");
    }

    @Test
    void createWardRequiresHospitalScope() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        WardRequestDTO request = WardRequestDTO.builder()
            .name("Maternity").code("MAT01").wardType(WardType.MATERNITY).build();

        assertThatThrownBy(() -> service.createWard(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Hospital context");
    }

    @Test
    void createWardPersistsTrimmedFields() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(wardRepository.existsByHospital_IdAndCodeIgnoreCase(hospitalId, "MAT02")).thenReturn(false);
        when(wardRepository.save(any(Ward.class))).thenAnswer(inv -> inv.getArgument(0));

        WardRequestDTO request = WardRequestDTO.builder()
            .name("  Maternity 2 ").code(" MAT02 ").wardType(WardType.MATERNITY).floor(2).build();

        WardResponseDTO created = service.createWard(request);

        assertThat(created.getName()).isEqualTo("Maternity 2");
        assertThat(created.getCode()).isEqualTo("MAT02");
        assertThat(created.getWardType()).isEqualTo("MATERNITY");
        assertThat(created.isActive()).isTrue();
    }

    @Test
    void updateWardCrossTenantReadsAsNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(wardRepository.findByIdAndHospital_Id(ward.getId(), hospitalId)).thenReturn(Optional.empty());

        WardRequestDTO request = WardRequestDTO.builder()
            .name("X").code("X").wardType(WardType.GENERAL).build();
        UUID wardId = ward.getId();

        assertThatThrownBy(() -> service.updateWard(wardId, request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteWardRejectsWardUsedByTransfers_D601() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(wardRepository.findByIdAndHospital_Id(ward.getId(), hospitalId)).thenReturn(Optional.of(ward));
        when(bedRepository.findByWard_Id(ward.getId())).thenReturn(List.of());
        when(transferOrderRepository.existsByFromWard_IdOrToWard_Id(ward.getId(), ward.getId())).thenReturn(true);

        UUID wardId = ward.getId();
        assertThatThrownBy(() -> service.deleteWard(wardId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("transfers");
        verify(wardRepository, never()).delete(any(Ward.class));
    }

    @Test
    void deleteBedRejectsFreeBedWithAdmissionHistory_D601() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        Bed bed = Bed.builder().ward(ward).bedNumber("B07").status(BedStatus.AVAILABLE).build();
        bed.setId(UUID.randomUUID());
        UUID bedId = bed.getId();
        when(bedRepository.findByIdAndWard_Hospital_Id(bedId, hospitalId)).thenReturn(Optional.of(bed));
        when(admissionRepository.existsByBed_Id(bedId)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteBed(bedId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("history");
        verify(bedRepository, never()).delete(any(Bed.class));
    }

    @Test
    void deleteBedRejectsFreeBedWithTransferHistory_D601() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        Bed bed = Bed.builder().ward(ward).bedNumber("B08").status(BedStatus.AVAILABLE).build();
        bed.setId(UUID.randomUUID());
        UUID bedId = bed.getId();
        when(bedRepository.findByIdAndWard_Hospital_Id(bedId, hospitalId)).thenReturn(Optional.of(bed));
        when(transferOrderRepository.existsByFromBed_IdOrToBed_Id(bedId, bedId)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteBed(bedId))
            .isInstanceOf(BusinessException.class);
        verify(bedRepository, never()).delete(any(Bed.class));
    }

    @Test
    void deleteBedDeletesFreeBedWithNoHistory_D601() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        Bed bed = Bed.builder().ward(ward).bedNumber("B09").status(BedStatus.AVAILABLE).build();
        bed.setId(UUID.randomUUID());
        UUID bedId = bed.getId();
        when(bedRepository.findByIdAndWard_Hospital_Id(bedId, hospitalId)).thenReturn(Optional.of(bed));

        service.deleteBed(bedId);

        verify(bedRepository).delete(bed);
    }

    @Test
    void deleteWardRejectsWardWithBeds() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(wardRepository.findByIdAndHospital_Id(ward.getId(), hospitalId)).thenReturn(Optional.of(ward));
        when(bedRepository.findByWard_Id(ward.getId()))
            .thenReturn(List.of(Bed.builder().ward(ward).bedNumber("B01").build()));

        UUID wardId = ward.getId();
        assertThatThrownBy(() -> service.deleteWard(wardId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("still has beds");
        verify(wardRepository, never()).delete(any(Ward.class));
    }

    /* ── Beds ──────────────────────────────────────────────────────────── */

    @Test
    void createBedRejectsDuplicateNumberInWard() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(wardRepository.findByIdAndHospital_Id(ward.getId(), hospitalId)).thenReturn(Optional.of(ward));
        when(bedRepository.existsByWard_IdAndBedNumberIgnoreCase(ward.getId(), "B01")).thenReturn(true);

        BedRequestDTO request = BedRequestDTO.builder().bedNumber("B01").build();
        UUID wardId = ward.getId();

        assertThatThrownBy(() -> service.createBed(wardId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("B01");
    }

    @Test
    void createBedBuildsLabelFromWardCode() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(wardRepository.findByIdAndHospital_Id(ward.getId(), hospitalId)).thenReturn(Optional.of(ward));
        when(bedRepository.existsByWard_IdAndBedNumberIgnoreCase(ward.getId(), "B03")).thenReturn(false);
        when(bedRepository.save(any(Bed.class))).thenAnswer(inv -> inv.getArgument(0));

        BedResponseDTO created = service.createBed(ward.getId(),
            BedRequestDTO.builder().bedNumber(" B03 ").bedType("Standard").build());

        assertThat(created.getBedNumber()).isEqualTo("B03");
        assertThat(created.getLabel()).isEqualTo("MAT01/B03");
        assertThat(created.getStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    void updateBedStatusRejectsManualOccupied() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        Bed bed = Bed.builder().ward(ward).bedNumber("B01").build();
        bed.setId(UUID.randomUUID());
        when(bedRepository.findByIdAndWard_Hospital_Id(bed.getId(), hospitalId)).thenReturn(Optional.of(bed));

        BedStatusUpdateRequestDTO request = BedStatusUpdateRequestDTO.builder()
            .status(BedStatus.OCCUPIED).build();
        UUID bedId = bed.getId();

        assertThatThrownBy(() -> service.updateBedStatus(bedId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("assignment");
    }

    @Test
    void updateBedStatusRejectsChangingOccupiedBed() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        Bed bed = Bed.builder().ward(ward).bedNumber("B01").status(BedStatus.OCCUPIED).build();
        bed.setId(UUID.randomUUID());
        when(bedRepository.findByIdAndWard_Hospital_Id(bed.getId(), hospitalId)).thenReturn(Optional.of(bed));

        BedStatusUpdateRequestDTO request = BedStatusUpdateRequestDTO.builder()
            .status(BedStatus.MAINTENANCE).build();
        UUID bedId = bed.getId();

        assertThatThrownBy(() -> service.updateBedStatus(bedId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("occupied");
    }

    @Test
    void updateBedStatusAppliesManualStates() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        Bed bed = Bed.builder().ward(ward).bedNumber("B01").build();
        bed.setId(UUID.randomUUID());
        when(bedRepository.findByIdAndWard_Hospital_Id(bed.getId(), hospitalId)).thenReturn(Optional.of(bed));
        when(bedRepository.save(bed)).thenReturn(bed);

        BedResponseDTO updated = service.updateBedStatus(bed.getId(),
            BedStatusUpdateRequestDTO.builder().status(BedStatus.MAINTENANCE).notes("Broken rail").build());

        assertThat(updated.getStatus()).isEqualTo("MAINTENANCE");
        assertThat(updated.getNotes()).isEqualTo("Broken rail");
    }

    @Test
    void deleteBedRejectsOccupiedBed() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        Bed bed = Bed.builder().ward(ward).bedNumber("B01").status(BedStatus.OCCUPIED).build();
        bed.setId(UUID.randomUUID());
        when(bedRepository.findByIdAndWard_Hospital_Id(bed.getId(), hospitalId)).thenReturn(Optional.of(bed));

        UUID bedId = bed.getId();
        assertThatThrownBy(() -> service.deleteBed(bedId))
            .isInstanceOf(BusinessException.class);
        verify(bedRepository, never()).delete(any(Bed.class));
    }

    @Test
    void getAvailableBedsRequiresHospitalScope() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);

        assertThatThrownBy(() -> service.getAvailableBeds())
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Hospital context");
    }

    @Test
    void getAvailableBedsListsAvailableActiveBeds() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        Bed bed = Bed.builder().ward(ward).bedNumber("B02").build();
        bed.setId(UUID.randomUUID());
        when(bedRepository.findByWard_Hospital_IdAndStatusAndActiveTrueOrderByBedNumberAsc(
            hospitalId, BedStatus.AVAILABLE)).thenReturn(List.of(bed));

        List<BedResponseDTO> beds = service.getAvailableBeds();

        assertThat(beds).singleElement()
            .extracting(BedResponseDTO::getLabel)
            .isEqualTo("MAT01/B02");
    }

    /** QA D609 (D431 in the picker): an adult man is not offered a maternity bed; a general bed stays. */
    @Test
    void d609_availableBedsForAPatient_leaveOutWardsTheyCannotTake() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        com.example.hms.model.Ward maternity = com.example.hms.model.Ward.builder()
            .name("Maternity").code("MATA").wardType(com.example.hms.enums.WardType.MATERNITY).build();
        com.example.hms.model.Ward general = com.example.hms.model.Ward.builder()
            .name("General").code("GEN").wardType(com.example.hms.enums.WardType.GENERAL).build();
        Bed m1 = Bed.builder().ward(maternity).bedNumber("M1").build();
        m1.setId(UUID.randomUUID());
        Bed g1 = Bed.builder().ward(general).bedNumber("G1").build();
        g1.setId(UUID.randomUUID());
        when(bedRepository.findByWard_Hospital_IdAndStatusAndActiveTrueOrderByBedNumberAsc(
            hospitalId, BedStatus.AVAILABLE)).thenReturn(List.of(m1, g1));
        UUID patientId = UUID.randomUUID();
        com.example.hms.model.Patient man = new com.example.hms.model.Patient();
        man.setId(patientId);
        man.setGender("MALE");
        man.setDateOfBirth(java.time.LocalDate.now().minusYears(40));
        when(patientRepository.findById(patientId)).thenReturn(java.util.Optional.of(man));

        assertThat(service.getAvailableBeds(patientId)).extracting(BedResponseDTO::getBedNumber).containsExactly("G1");
        // Without a patient the full list is unchanged (the bed board, the ward views).
        assertThat(service.getAvailableBeds(null)).hasSize(2);
    }
}
