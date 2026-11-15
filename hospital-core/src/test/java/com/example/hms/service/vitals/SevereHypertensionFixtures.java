package com.example.hms.service.vitals;

import com.example.hms.model.Hospital;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.repository.DeliveryRecordRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Test wiring for the shared severe-blood-pressure rule. */
public final class SevereHypertensionFixtures {

    private SevereHypertensionFixtures() {
    }

    /**
     * The rule with no record-access policy wired: only the acting hospital's records are read (the
     * evaluator's own fallback), and no reach is recorded.
     */
    public static SevereHypertension actingHospitalOnly(DeliveryRecordRepository deliveries) {
        return new SevereHypertension(deliveries, null, null, null, null);
    }

    /** A delivery record at {@code hospitalId}. */
    public static DeliveryRecord birth(UUID hospitalId, LocalDateTime at) {
        Hospital hospital = null;
        if (hospitalId != null) {
            hospital = Hospital.builder().build();
            hospital.setId(hospitalId);
        }
        return DeliveryRecord.builder().hospital(hospital).birthDateTime(at).build();
    }

    /**
     * Stubs a birth on record for this mother at {@code hospitalId}: the repository answers for the
     * exact hospitals and window it is asked, as the real query does.
     */
    public static void bornOn(DeliveryRecordRepository deliveries, UUID patientId, UUID hospitalId, LocalDateTime at) {
        DeliveryRecord delivery = birth(hospitalId, at);
        when(deliveries.findFirstByPatient_IdAndHospital_IdInAndBirthDateTimeBetweenOrderByBirthDateTimeDesc(
            eq(patientId), any(), any(), any())).thenAnswer(inv -> {
                Collection<UUID> hospitals = inv.getArgument(1);
                LocalDateTime from = inv.getArgument(2);
                LocalDateTime to = inv.getArgument(3);
                boolean found = hospitals.contains(hospitalId) && !at.isBefore(from) && !at.isAfter(to);
                return found ? Optional.of(delivery) : Optional.empty();
            });
    }
}
