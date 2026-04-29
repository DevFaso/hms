package com.example.hms.repository.platform;

import com.example.hms.model.platform.MllpAllowedSender;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MllpAllowedSenderRepository extends JpaRepository<MllpAllowedSender, UUID> {

    Optional<MllpAllowedSender> findBySendingApplicationIgnoreCaseAndSendingFacilityIgnoreCaseAndActiveTrue(
        String sendingApplication, String sendingFacility);

    Optional<MllpAllowedSender> findBySendingApplicationIgnoreCaseAndSendingFacilityIgnoreCase(
        String sendingApplication, String sendingFacility);

    List<MllpAllowedSender> findAllByOrderBySendingFacilityAscSendingApplicationAsc();

    List<MllpAllowedSender> findAllByHospital_IdOrderBySendingFacilityAsc(UUID hospitalId);
}
