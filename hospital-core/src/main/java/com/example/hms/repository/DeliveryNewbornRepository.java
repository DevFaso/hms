package com.example.hms.repository;

import com.example.hms.model.labor.DeliveryNewborn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** QA D1592: the babies a delivery registered as patients. */
@Repository
public interface DeliveryNewbornRepository extends JpaRepository<DeliveryNewborn, UUID> {

    List<DeliveryNewborn> findByDeliveryRecord_IdOrderByBirthOrderAsc(UUID deliveryRecordId);

    /** The delivery a baby was registered from, if any. */
    Optional<DeliveryNewborn> findByNewbornPatient_Id(UUID newbornPatientId);
}
