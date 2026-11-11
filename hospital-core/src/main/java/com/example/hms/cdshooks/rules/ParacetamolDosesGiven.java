package com.example.hms.cdshooks.rules;

import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.MedicationAdministrationRecordRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * CR-03: the paracetamol recorded as given: the last 24 hours by the clock, across midnight, at this
 * hospital, from any order whatever became of it. The MAR has no return or correction of a dose (a
 * GIVEN row is final), so there is nothing to net off; a dose given elsewhere, before a transfer or
 * outside the hospital is not on record, and the card says so. Called by {@link ParacetamolFactsReader},
 * inside its transaction.
 */
@Service
public class ParacetamolDosesGiven {

    private static final Duration ROLLING_WINDOW = Duration.ofHours(24);

    private final MedicationAdministrationRecordRepository administrations;
    private Clock clock = Clock.systemDefaultZone();

    public ParacetamolDosesGiven(MedicationAdministrationRecordRepository administrations) {
        this.administrations = administrations;
    }

    /** For a test at a chosen hour. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** A dose of another drug is not read; one this cannot place is named. */
    ParacetamolFacts.Documented read(UUID patientId, UUID hospitalId, List<MedicationCatalogItem> paracetamol,
                                     List<MedicationCatalogItem> combinations, List<MedicationCatalogItem> active) {
        LocalDateTime since = LocalDateTime.now(clock).minus(ROLLING_WINDOW);
        List<ParacetamolFacts.Dose> doses = new ArrayList<>();
        Set<String> unreadable = new LinkedHashSet<>();
        for (MedicationAdministrationRecord given : administrations.findGivenSince(patientId, hospitalId, since)) {
            Prescription rx = given.getPrescription();
            if (ParacetamolFactsReader.isCombination(rx, paracetamol, combinations)) {
                unreadable.add(rx.getMedicationName());
                continue;
            }
            ParacetamolFacts.OtherOrder alone = ParacetamolFactsReader.otherOrder(rx, paracetamol);
            if (alone != null) {
                doses.add(new ParacetamolFacts.Dose(given.getAdministeredAt(), rx.getMedicationName(),
                    readableDose(given, rx), alone.item()));
            } else if (cannotBePlaced(rx, active)) {
                unreadable.add(rx.getMedicationName());
            }
        }
        return new ParacetamolFacts.Documented(true, List.copyOf(doses), List.copyOf(unreadable));
    }

    /** A name nothing knows, or a link to an item no longer active: it may hold paracetamol, so it is named. */
    private static boolean cannotBePlaced(Prescription rx, List<MedicationCatalogItem> active) {
        if (rx.getMedicationName() == null) {
            return false;
        }
        UUID linked = rx.getMedicationCatalogItemId();
        return linked == null ? !ParacetamolFactsReader.recognised(rx.getMedicationName(), active)
            : active.stream().noneMatch(i -> linked.equals(i.getId()));
    }

    /** What the row says was given; the order's dose when the row has only "See order". */
    private static String readableDose(MedicationAdministrationRecord given, Prescription rx) {
        String dose = given.getDose();
        return dose == null || dose.isBlank() || !Character.isDigit(dose.trim().charAt(0)) ? rx.getDosage() : dose;
    }
}
