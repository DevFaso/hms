package com.example.hms.repository;

import com.example.hms.enums.ProxyStatus;
import com.example.hms.model.PatientProxy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientProxyRepository extends JpaRepository<PatientProxy, UUID> {

    /** Proxies granted BY a patient (I granted others access to my data) */
    Page<PatientProxy> findByGrantorPatientIdAndStatus(UUID grantorPatientId, ProxyStatus status, Pageable pageable);

    List<PatientProxy> findByGrantorPatientIdAndStatus(UUID grantorPatientId, ProxyStatus status);

    /** Proxies granted TO a user (I can view someone else's data) */
    Page<PatientProxy> findByProxyUserIdAndStatus(UUID proxyUserId, ProxyStatus status, Pageable pageable);

    List<PatientProxy> findByProxyUserIdAndStatus(UUID proxyUserId, ProxyStatus status);

    /** Check for duplicate active proxy */
    Optional<PatientProxy> findByGrantorPatientIdAndProxyUserIdAndStatus(
            UUID grantorPatientId, UUID proxyUserId, ProxyStatus status);
}
