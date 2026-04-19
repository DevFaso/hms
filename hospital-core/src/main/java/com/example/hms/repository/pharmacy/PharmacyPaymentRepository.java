package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.PharmacyPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PharmacyPaymentRepository extends JpaRepository<PharmacyPayment, UUID> {

    Page<PharmacyPayment> findByDispenseId(UUID dispenseId, Pageable pageable);

    Page<PharmacyPayment> findByPatientId(UUID patientId, Pageable pageable);
}
