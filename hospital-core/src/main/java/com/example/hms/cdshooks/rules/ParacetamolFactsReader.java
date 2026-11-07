package com.example.hms.cdshooks.rules;

import com.example.hms.enums.ProblemStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.service.InpatientOrderEnd;
import com.example.hms.service.allergy.DrugClassAllergy;
import com.example.hms.service.MedicationCourseStatus;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.impl.MarDoseSchedule;
import com.example.hms.service.recordaccess.CrossHospitalRows;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The persistence behind {@link AdultParacetamolRule}. In its own read-only transaction: a CDS
 * hook runs with no session (open-in-view is off), and whether an order ended with its stay reads
 * the order's LAZY encounter (QA D1121). Nothing is read for a child or for another drug.
 *
 * <p>Orders, problems and catalogue are read at the hospital acting on the request, when there is
 * one: a CDS hook passes {@code Patient.hospitalId}, the FIRST hospital that registered the patient,
 * which no access rule may key on. A row the acting hospital may not see never reaches a card
 * ({@link CrossHospitalRows}), and no hospital at all reads nothing.
 *
 * <p>What "active" means here, since nothing on an order says it is over (proposed for clinician
 * sign-off): not stopped; not past the duration it states, in English, French or Spanish; not ended
 * with its stay; a one-off only for 24 hours; and an order with no readable duration only for
 * {@link #NO_DURATION_WINDOW}, the window the duplicate-order check already uses, or for as long as
 * it is filed on a stay that is still open. Past that window it is not counted, and it is not
 * dropped either: nothing shows it was stopped, so the card names it as possibly still in use.
 * A combination product is named too, never counted.
 */
@Service
public class ParacetamolFactsReader {

    private static final Duration NO_DURATION_WINDOW = DuplicateMedicationOrderRule.LOOKBACK;
    private static final Duration ONE_OFF_WINDOW = Duration.ofHours(24);
    private static final int DAYS_PER_WEEK = 7;
    private static final Pattern DURATION = Pattern.compile(
        "(\\d{1,5}+)\\s*+(jours?+|j|days?+|dias?+|semaines?+|sem|weeks?+|semanas?+|mois|months?+|mes|meses)\\b");

    private final PrescriptionRepository prescriptions;
    private final MedicationCatalogItemRepository catalogue;
    private final PatientProblemRepository problems;
    private final AdmissionRepository admissions;

    public ParacetamolFactsReader(PrescriptionRepository prescriptions, MedicationCatalogItemRepository catalogue,
                                  PatientProblemRepository problems, AdmissionRepository admissions) {
        this.prescriptions = prescriptions;
        this.catalogue = catalogue;
        this.problems = problems;
        this.admissions = admissions;
    }

    /**
     * @param item          the proposed order's catalogue item (may be null)
     * @param editedOrderId the order being edited, not "another order" of itself (null on a create)
     */
    @Transactional(readOnly = true, noRollbackFor = RuntimeException.class)
    public ParacetamolFacts read(Patient patient, UUID hospitalId, String medicationName,
                                 MedicationCatalogItem item, UUID editedOrderId) {
        if (patient == null) {
            return ParacetamolFacts.NONE;
        }
        // RCP 4.3 is not limited by age: a child's facts hold the problems, for the contraindication only.
        boolean child = patient.getDateOfBirth() != null && !PediatricDoseRule.isAdult(patient);
        UUID acting = actingHospital();
        UUID scope = acting != null ? acting : hospitalId;
        boolean named = ParacetamolIdentity.names(medicationName, item);
        boolean combination = ParacetamolIdentity.isCombinationOrder(medicationName, item);
        // An item that names another drug settles it, with no catalogue read.
        if (!named && !combination && (item != null || medicationName == null || medicationName.isBlank())) {
            return ParacetamolFacts.NONE;
        }
        List<MedicationCatalogItem> active = activeCatalogue(scope);
        List<MedicationCatalogItem> paracetamol = active.stream().filter(ParacetamolIdentity::isParacetamolAlone).toList();
        List<MedicationCatalogItem> combinations = active.stream().filter(ParacetamolIdentity::isCombination).toList();
        if (!named && !combination) {
            named = paracetamol.stream().anyMatch(i -> ParacetamolIdentity.namesCatalogueItem(medicationName, i));
            combination = namesACombination(medicationName, paracetamol, combinations);
        }
        if (!named && !combination) {
            return ParacetamolFacts.NONE;
        }
        Orders orders = child ? new Orders(List.of(), List.of(), List.of())
            : otherOrders(patient.getId(), scope, editedOrderId, paracetamol, combinations);
        List<PatientProblem> live = liveProblems(patient.getId(), scope);
        List<PatientProblem> surfaced = live.stream()
            .filter(p -> CrossHospitalRows.maySurface(scope, acting, p.getSensitivityCategory()))
            .toList();
        return new ParacetamolFacts(true, orders.counted, ParacetamolRisk.of(surfaced), orders.mayStillBeInUse,
            orders.combinations, combination, live.size() - surfaced.size(), scope == null);
    }

    private static boolean namesACombination(String name, List<MedicationCatalogItem> paracetamol,
                                             List<MedicationCatalogItem> combinations) {
        return paracetamol.stream().anyMatch(i -> ParacetamolIdentity.namesItWithAnother(name, i))
            || combinations.stream().anyMatch(i -> ParacetamolIdentity.namesCatalogueItem(name, i)
                || ParacetamolIdentity.namesItWithAnother(name, i));
    }

    /** The orders, by what the total does with them. */
    private record Orders(List<ParacetamolFacts.OtherOrder> counted, List<String> mayStillBeInUse, List<String> combinations) {
    }

    /** What the order's dates say about it. */
    private enum Running { COUNTED, COUNTED_NO_DURATION, MAY_STILL_BE_IN_USE, ENDED }

    private static UUID actingHospital() {
        HospitalContext context = HospitalContextHolder.getContext().orElse(null);
        return context == null ? null : context.getActiveHospitalId();
    }

    /** This hospital's and the national list's active items. */
    private List<MedicationCatalogItem> activeCatalogue(UUID hospitalId) {
        if (hospitalId == null) {
            return List.of();
        }
        Page<MedicationCatalogItem> page = catalogue.findActivePage(hospitalId, Pageable.unpaged());
        return page == null ? List.of() : page.getContent();
    }

    private Orders otherOrders(UUID patientId, UUID hospitalId, UUID editedOrderId,
                               List<MedicationCatalogItem> paracetamol, List<MedicationCatalogItem> combinations) {
        if (hospitalId == null) {
            return new Orders(List.of(), List.of(), List.of());
        }
        List<Prescription> live = prescriptions.findByPatient_IdAndHospital_Id(patientId, hospitalId).stream()
            .filter(rx -> rx.getStatus() != null && !CdsRuleEngine.isTerminal(rx.getStatus()))
            .filter(rx -> !rx.getId().equals(editedOrderId))
            .toList();
        List<Prescription> combined = live.stream().filter(rx -> isCombination(rx, paracetamol, combinations)).toList();
        List<ParacetamolFacts.OtherOrder> alone = live.stream()
            .filter(rx -> !combined.contains(rx))
            .map(rx -> otherOrder(rx, paracetamol))
            .filter(Objects::nonNull)
            .toList();
        if (alone.isEmpty() && combined.isEmpty()) {
            return new Orders(List.of(), List.of(), List.of());
        }
        List<Admission> closedStays = admissions.findByPatientIdOrderByAdmissionDateTimeDesc(patientId).stream()
            .filter(a -> a.getActualDischargeDateTime() != null)
            .toList();
        List<ParacetamolFacts.OtherOrder> counted = new java.util.ArrayList<>();
        java.util.Set<String> mayStillBeInUse = new java.util.LinkedHashSet<>();
        for (ParacetamolFacts.OtherOrder other : alone) {
            Running running = running(other.order(), closedStays);
            if (running == Running.MAY_STILL_BE_IN_USE) {
                mayStillBeInUse.add(other.order().getMedicationName());
            } else if (running != Running.ENDED) {
                counted.add(new ParacetamolFacts.OtherOrder(other.order(), other.item(), running == Running.COUNTED));
            }
        }
        List<String> combinedNames = combined.stream()
            .filter(rx -> running(rx, closedStays) != Running.ENDED)
            .map(Prescription::getMedicationName)
            .distinct()
            .toList();
        return new Orders(counted, List.copyOf(mayStillBeInUse), combinedNames);
    }

    private static boolean isCombination(Prescription rx, List<MedicationCatalogItem> paracetamol,
                                         List<MedicationCatalogItem> combinations) {
        // A link to paracetamol alone wins over reading the name.
        if (paracetamol.stream().anyMatch(i -> carries(rx, i))) {
            return false;
        }
        String name = rx.getMedicationName();
        return ParacetamolIdentity.isCombinationName(name) || combinations.stream().anyMatch(i -> carries(rx, i))
            || namesACombination(name, paracetamol, combinations);
    }

    /** The order as a paracetamol order, or {@code null} when it is another drug. */
    private static ParacetamolFacts.OtherOrder otherOrder(Prescription rx, List<MedicationCatalogItem> paracetamol) {
        List<MedicationCatalogItem> linked = paracetamol.stream().filter(i -> carries(rx, i)).toList();
        if (!linked.isEmpty()) {
            return new ParacetamolFacts.OtherOrder(rx, linked.get(0));
        }
        List<MedicationCatalogItem> named = paracetamol.stream()
            .filter(i -> ParacetamolIdentity.namesCatalogueItem(rx.getMedicationName(), i))
            .toList();
        if (named.isEmpty()) {
            return ParacetamolIdentity.nameIsParacetamolAlone(rx.getMedicationName())
                ? new ParacetamolFacts.OtherOrder(rx, null) : null;
        }
        boolean oneStrength = named.stream()
            .map(i -> Objects.toString(CdsRuleEngine.strengthMgOf(i))).distinct().count() == 1;
        return new ParacetamolFacts.OtherOrder(rx, oneStrength ? named.get(0) : null);
    }

    private static boolean carries(Prescription rx, MedicationCatalogItem item) {
        if (rx.getMedicationCatalogItemId() != null) {
            return rx.getMedicationCatalogItemId().equals(item.getId());
        }
        return rx.getMedicationCode() != null && rx.getMedicationCode().equals(item.getCode());
    }

    /** See the class comment. A draft counts: it is about to be given. */
    private static Running running(Prescription rx, List<Admission> closedStays) {
        if (InpatientOrderEnd.endedWithAStay(rx, closedStays)) {
            return Running.ENDED;
        }
        LocalDateTime written = rx.getCreatedAt();
        if (written == null) {
            return Running.COUNTED_NO_DURATION;
        }
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        if (MarDoseSchedule.read(rx).kind() == MarDoseSchedule.Kind.ONE_OFF) {
            return written.plus(ONE_OFF_WINDOW).isBefore(now) ? Running.ENDED : Running.COUNTED;
        }
        LocalDate end = endDate(rx, written.toLocalDate());
        if (end != null) {
            return end.isBefore(now.toLocalDate()) ? Running.ENDED : Running.COUNTED;
        }
        return onAnOpenStay(rx) || !written.plus(NO_DURATION_WINDOW).isBefore(now)
            ? Running.COUNTED_NO_DURATION : Running.MAY_STILL_BE_IN_USE;
    }

    /** The stated end: {@code MedicationCourseStatus}'s reading, else the same in French and Spanish. */
    private static LocalDate endDate(Prescription rx, LocalDate start) {
        LocalDate end = MedicationCourseStatus.endDate(rx);
        if (end != null || rx.getDuration() == null) {
            return end;
        }
        Matcher m = DURATION.matcher(DrugClassAllergy.normalize(rx.getDuration()));
        if (!m.find()) {
            return null;
        }
        int n = Integer.parseInt(m.group(1));
        String unit = m.group(2);
        if (n == 0) {
            return null;
        }
        if (unit.startsWith("mo") || unit.startsWith("me")) {
            return start.plusMonths(n);
        }
        boolean weeks = unit.startsWith("sem") || unit.startsWith("week");
        return start.plusDays((long) n * (weeks ? DAYS_PER_WEEK : 1));
    }

    private static boolean onAnOpenStay(Prescription rx) {
        return rx.getEncounter() != null && rx.getEncounter().getAdmissionId() != null;
    }

    /** Active rows at this hospital, never one removed as entered in error. */
    private List<PatientProblem> liveProblems(UUID patientId, UUID hospitalId) {
        if (hospitalId == null) {
            return List.of();
        }
        return problems.findByPatient_IdAndHospital_Id(patientId, hospitalId).stream()
            .filter(p -> !p.isEnteredInError())
            .filter(p -> p.getStatus() == null || p.getStatus() == ProblemStatus.ACTIVE
                || p.getStatus() == ProblemStatus.RECURRENCE)
            .toList();
    }
}
