package com.example.hms.service.pharmacy;

import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseResponseDTO;
import com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface DispenseService {

    DispenseResponseDTO createDispense(DispenseRequestDTO dto);

    /**
     * Transactional body of {@link #createDispense}. Exposed on the interface
     * (not on the impl alone) so a Spring AOP proxy can intercept the
     * self-invocation that {@link #createDispense} performs to enforce
     * idempotency-key race recovery — see DispenseServiceImpl Javadoc for
     * the three-path flow. Callers should NOT invoke this directly; they
     * lose pre-check + race-recovery semantics if they do.
     */
    DispenseResponseDTO createDispenseTransactionally(DispenseRequestDTO dto);

    /**
     * G15: prepare a fill (stock set aside, status PENDING) and tell the
     * patient it is ready for collection. 404 when
     * {@code pharmacy.ready-for-collection.enabled} is off.
     */
    DispenseResponseDTO markReadyForCollection(DispenseRequestDTO dto);

    /** Transactional body of {@link #markReadyForCollection}; same contract as {@link #createDispenseTransactionally}. */
    DispenseResponseDTO markReadyForCollectionTransactionally(DispenseRequestDTO dto);

    /** {@code pharmacy.ready-for-collection.enabled}, for {@code GET /pharmacy/dispense/settings}. */
    boolean isReadyForCollectionEnabled();

    DispenseResponseDTO getDispense(UUID id);

    Page<DispenseResponseDTO> listByPrescription(UUID prescriptionId, Pageable pageable);

    Page<DispenseResponseDTO> listByPatient(UUID patientId, Pageable pageable);

    Page<DispenseResponseDTO> listByPharmacy(UUID pharmacyId, Pageable pageable);

    DispenseResponseDTO cancelDispense(UUID id);

    /** Paginated list of prescriptions ready to dispense at the caller's active hospital. */
    Page<WorkQueuePrescriptionDTO> getWorkQueue(Pageable pageable);
}
