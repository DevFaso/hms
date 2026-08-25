package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.BedStatus;
import com.example.hms.enums.WardType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Admission;
import com.example.hms.model.Bed;
import com.example.hms.model.Hospital;
import com.example.hms.model.Ward;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.BedRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BedAssignmentServiceTest {

    @Mock private BedRepository bedRepository;
    @Mock private AdmissionRepository admissionRepository;

    @InjectMocks private BedAssignmentService service;

    private UUID hospitalId;
    private Admission admission;
    private Ward ward;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);

        ward = Ward.builder().hospital(hospital).name("Maternity").code("MAT01")
            .wardType(WardType.MATERNITY).build();

        admission = new Admission();
        admission.setId(UUID.randomUUID());
        admission.setHospital(hospital);
        admission.setStatus(AdmissionStatus.ACTIVE);
    }

    private Bed bed(String number, BedStatus status) {
        Bed bed = Bed.builder().ward(ward).bedNumber(number).status(status).build();
        bed.setId(UUID.randomUUID());
        return bed;
    }

    @Test
    void assignBedOccupiesBedAndDerivesRoomBedLabel() {
        Bed bed = bed("B03", BedStatus.AVAILABLE);
        when(bedRepository.findByIdAndWard_Hospital_Id(bed.getId(), hospitalId))
            .thenReturn(Optional.of(bed));

        service.assignBed(admission, bed.getId());

        assertThat(bed.getStatus()).isEqualTo(BedStatus.OCCUPIED);
        assertThat(admission.getBed()).isSameAs(bed);
        assertThat(admission.getRoomBed()).isEqualTo("MAT01/B03");
        verify(bedRepository).save(bed);
    }

    @Test
    void assignBedReleasesPreviousBed() {
        Bed previous = bed("B01", BedStatus.OCCUPIED);
        admission.setBed(previous);
        Bed next = bed("B02", BedStatus.AVAILABLE);
        when(bedRepository.findByIdAndWard_Hospital_Id(next.getId(), hospitalId))
            .thenReturn(Optional.of(next));

        service.assignBed(admission, next.getId());

        assertThat(previous.getStatus()).isEqualTo(BedStatus.AVAILABLE);
        assertThat(next.getStatus()).isEqualTo(BedStatus.OCCUPIED);
        assertThat(admission.getBed()).isSameAs(next);
    }

    @Test
    void assignBedRejectsUnavailableBed() {
        Bed bed = bed("B04", BedStatus.MAINTENANCE);
        when(bedRepository.findByIdAndWard_Hospital_Id(bed.getId(), hospitalId))
            .thenReturn(Optional.of(bed));

        UUID bedId = bed.getId();
        assertThatThrownBy(() -> service.assignBed(admission, bedId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not available");
    }

    @Test
    void assignBedRejectsDischargedAdmission() {
        admission.setStatus(AdmissionStatus.DISCHARGED);
        UUID bedId = UUID.randomUUID();

        assertThatThrownBy(() -> service.assignBed(admission, bedId))
            .isInstanceOf(BusinessException.class);
        verify(bedRepository, never()).save(any(Bed.class));
    }

    @Test
    void assignBedCrossHospitalReadsAsNotFound() {
        UUID bedId = UUID.randomUUID();
        when(bedRepository.findByIdAndWard_Hospital_Id(bedId, hospitalId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignBed(admission, bedId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void assignBedIsIdempotentForSameBed() {
        Bed bed = bed("B05", BedStatus.OCCUPIED);
        admission.setBed(bed);
        when(bedRepository.findByIdAndWard_Hospital_Id(bed.getId(), hospitalId))
            .thenReturn(Optional.of(bed));

        service.assignBed(admission, bed.getId());

        assertThat(bed.getStatus()).isEqualTo(BedStatus.OCCUPIED);
        verify(bedRepository, never()).save(any(Bed.class));
    }

    @Test
    void releaseBedFreesBedButKeepsHistoricalPointer() {
        Bed bed = bed("B06", BedStatus.OCCUPIED);
        admission.setBed(bed);
        admission.setRoomBed("MAT01/B06");

        service.releaseBed(admission);

        assertThat(bed.getStatus()).isEqualTo(BedStatus.AVAILABLE);
        assertThat(admission.getBed()).isSameAs(bed); // history kept
        assertThat(admission.getRoomBed()).isEqualTo("MAT01/B06");
        verify(bedRepository).save(bed);
    }

    @Test
    void unassignBedClearsPointerAndLabel() {
        Bed bed = bed("B07", BedStatus.OCCUPIED);
        admission.setBed(bed);
        admission.setRoomBed("MAT01/B07");

        service.unassignBed(admission);

        assertThat(bed.getStatus()).isEqualTo(BedStatus.AVAILABLE);
        assertThat(admission.getBed()).isNull();
        assertThat(admission.getRoomBed()).isNull();
    }

    @Test
    void releaseBedNoOpsWithoutBed() {
        service.releaseBed(admission);
        verify(bedRepository, never()).save(any(Bed.class));
    }

    @Test
    void releaseBedsForPatientReleasesAllHeldBeds() {
        Bed bed = bed("B08", BedStatus.OCCUPIED);
        admission.setBed(bed);
        UUID patientId = UUID.randomUUID();
        when(admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(
            any(UUID.class), any(UUID.class), any()))
            .thenReturn(List.of(admission));

        service.releaseBedsForPatient(patientId, hospitalId);

        assertThat(bed.getStatus()).isEqualTo(BedStatus.AVAILABLE);
        verify(bedRepository).save(bed);
    }

    // ── Reservations, for transfer orders (Tier 2 item 30) ──────────────
    //
    // These write Bed.status, so they belong to this class rather than the
    // transfer service — and they need testing HERE, because
    // TransferServiceImplTest mocks this class and therefore exercises none
    // of the actual state transitions.

    @Test
    void reserveBedHoldsAnAvailableBedWithoutOccupyingIt() {
        Bed bed = bed("B07", BedStatus.AVAILABLE);
        when(bedRepository.findByIdAndWard_Hospital_Id(bed.getId(), hospitalId))
            .thenReturn(Optional.of(bed));

        Bed held = service.reserveBed(bed.getId(), hospitalId);

        // RESERVED, not OCCUPIED: nobody is in it yet, and the census counts
        // the two separately.
        assertThat(held.getStatus()).isEqualTo(BedStatus.RESERVED);
        verify(bedRepository).save(bed);
    }

    @Test
    void reserveBedRefusesABedSomebodyIsAlreadyIn() {
        Bed bed = bed("B07", BedStatus.OCCUPIED);
        when(bedRepository.findByIdAndWard_Hospital_Id(bed.getId(), hospitalId))
            .thenReturn(Optional.of(bed));
        UUID bedId = bed.getId();

        assertThatThrownBy(() -> service.reserveBed(bedId, hospitalId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not available");
        assertThat(bed.getStatus()).isEqualTo(BedStatus.OCCUPIED);
    }

    @Test
    void reserveBedRefusesABedAtAnotherHospital() {
        UUID bedId = UUID.randomUUID();
        when(bedRepository.findByIdAndWard_Hospital_Id(bedId, hospitalId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserveBed(bedId, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aReservedBedCanBeClaimedByTheTransferThatHeldIt() {
        // The completion path: the destination has been RESERVED since the
        // order was raised, so plain AVAILABLE-only assignment would refuse it.
        Bed bed = bed("B08", BedStatus.RESERVED);
        when(bedRepository.findByIdAndWard_Hospital_Id(bed.getId(), hospitalId))
            .thenReturn(Optional.of(bed));

        service.assignBed(admission, bed.getId(),
            Set.of(BedStatus.AVAILABLE, BedStatus.RESERVED));

        assertThat(bed.getStatus()).isEqualTo(BedStatus.OCCUPIED);
        assertThat(admission.getBed()).isEqualTo(bed);
        assertThat(admission.getRoomBed()).isEqualTo("MAT01/B08");
    }

    @Test
    void ordinaryAssignmentStillRefusesAReservedBed() {
        // Somebody else's transfer is on its way to it.
        Bed bed = bed("B08", BedStatus.RESERVED);
        when(bedRepository.findByIdAndWard_Hospital_Id(bed.getId(), hospitalId))
            .thenReturn(Optional.of(bed));
        UUID bedId = bed.getId();

        assertThatThrownBy(() -> service.assignBed(admission, bedId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not available");
        assertThat(bed.getStatus()).isEqualTo(BedStatus.RESERVED);
    }

    @Test
    void releaseReservationHandsAHeldBedBack() {
        Bed bed = bed("B09", BedStatus.RESERVED);

        service.releaseReservation(bed);

        assertThat(bed.getStatus()).isEqualTo(BedStatus.AVAILABLE);
        verify(bedRepository).save(bed);
    }

    @Test
    void releaseReservationWillNotEvictSomebodyWhoIsActuallyInTheBed() {
        // Cancelling a stale transfer must not turf out a patient who has
        // since been put in the destination.
        Bed bed = bed("B09", BedStatus.OCCUPIED);

        service.releaseReservation(bed);

        assertThat(bed.getStatus()).isEqualTo(BedStatus.OCCUPIED);
        verify(bedRepository, never()).save(bed);
    }

    @Test
    void releaseReservationToleratesNoBed() {
        service.releaseReservation(null);

        verify(bedRepository, never()).save(any());
    }
}
