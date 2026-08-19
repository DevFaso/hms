package com.example.hms.repository.platform;

import com.example.hms.model.platform.MllpAllowedSender;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Sender app / facility values are stored upper-case canonical (see
 * {@code MllpAllowedSenderMapper#normalizeSenderField} and the V62
 * CHECK constraints), so the runtime lookups are case-sensitive and
 * benefit from the partial unique index on the same columns. Callers
 * that take raw MSH-3 / MSH-4 values are responsible for upper-casing
 * before calling these methods — see
 * {@code MllpAllowedSenderServiceImpl#resolveHospital}.
 */
public interface MllpAllowedSenderRepository extends JpaRepository<MllpAllowedSender, UUID> {

    Optional<MllpAllowedSender> findBySendingApplicationAndSendingFacilityAndActiveTrue(
        String sendingApplication, String sendingFacility);

    Optional<MllpAllowedSender> findBySendingApplicationAndSendingFacility(
        String sendingApplication, String sendingFacility);

    List<MllpAllowedSender> findAllByOrderBySendingFacilityAscSendingApplicationAsc();

    List<MllpAllowedSender> findAllByHospital_IdOrderBySendingFacilityAsc(UUID hospitalId);
}
