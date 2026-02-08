package com.example.hms.repository;

import com.example.hms.enums.ObgynReferralStatus;
import com.example.hms.model.referral.ObgynReferral;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.UUID;

public interface ObgynReferralRepository extends JpaRepository<ObgynReferral, UUID> {
    Page<ObgynReferral> findByPatient_Id(UUID patientId, Pageable pageable);
    Page<ObgynReferral> findByHospital_Id(UUID hospitalId, Pageable pageable);
    Page<ObgynReferral> findByObgyn_Id(UUID userId, Pageable pageable);

    long countByStatus(ObgynReferralStatus status);
    long countByStatusAndSlaDueAtBefore(ObgynReferralStatus status, LocalDateTime threshold);
}
