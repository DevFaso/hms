package com.example.hms.cdshooks.rules;

import com.example.hms.enums.ProblemStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.MedicationAdministrationRecordRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.service.InpatientOrderEnd;
import com.example.hms.service.allergy.AllergyDrugIdentity;
import com.example.hms.service.allergy.DrugClassAllergy;
import com.example.hms.service.MedicationCourseStatus;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.impl.MarDoseSchedule;
import com.example.hms.service.pharmacy.OrderedDrugIdentity;
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
 * the order's LAZY encounter (QA D1121). Nothing is read for another drug, and a child's facts hold
 * only the problems, for the contraindication.
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
 *
 * <p>CR-03: the doses recorded as given are read apart from all that: the last 24 hours by the clock,
 * across midnight, at this hospital, from any order whatever became of it. The MAR has no return or
 * correction of a dose (a GIVEN row is final), so there is nothing to net off; a dose given elsewhere,
 * before a transfer or outside the hospital is not on record, and the card says so.
 */
@Service
public class ParacetamolFactsReader {

    private static final Duration NO_DURATION_WINDOW = DuplicateMedicationOrderRule.LOOKBACK;
    private static final Duration ONE_OFF_WINDOW = Duration.ofHours(24);
    private static final Duration ROLLING_WINDOW = Duration.ofHours(24);
    private static final int DAYS_PER_WEEK = 7;
    private static final Pattern DURATION = Pattern.compile(
        "(\\d{1,5}+)\\s*+(jours?+|j|days?+|dias?+|semaines?+|sem|weeks?+|semanas?+|mois|months?+|mes|meses)\\b");

    private final PrescriptionRepository prescriptions;
    private final MedicationCatalogItemRepository catalogue;
    private final PatientProblemRepository problems;
    private final AdmissionRepository admissions;
    private final MedicationAdministrationRecordRepository administrations;
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    /** No record of the doses given: a card then says that exposure is unknown. */
    public ParacetamolFactsReader(PrescriptionRepository prescriptions, MedicationCatalogItemRepository catalogue,
                                  PatientProblemRepository problems, AdmissionRepository admissions) {
        this(prescriptions, catalogue, problems, admissions, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ParacetamolFactsReader(PrescriptionRepository prescriptions, MedicationCatalogItemRepository catalogue,
                                  PatientProblemRepository problems, AdmissionRepository admissions,
                                  MedicationAdministrationRecordRepository administrations) {
        this.prescriptions = prescriptions;
        this.catalogue = catalogue;
        this.problems = problems;
        this.admissions = admissions;
        this.administrations = administrations;
    }

    /** For a test at a chosen hour. */
    void setClock(java.time.Clock clock) {
        this.clock = clock;
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
        UUID acting = ActingScopeResolver.pinnedHospitalIdOrNull();
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
            return scope != null && !recognised(medicationName, active)
                ? ParacetamolFacts.NOT_RECOGNISED : ParacetamolFacts.NONE;
        }
        Orders orders = child ? new Orders(List.of(), List.of(), List.of(), List.of())
            : otherOrders(patient.getId(), scope, editedOrderId, paracetamol, combinations, active);
        List<PatientProblem> live = liveProblems(patient.getId(), scope);
        List<PatientProblem> surfaced = live.stream()
            .filter(p -> CrossHospitalRows.maySurface(scope, acting, p.getSensitivityCategory()))
            .toList();
        ParacetamolFacts.Documented documented = child ? ParacetamolFacts.Documented.NOT_READ
            : documented(patient.getId(), scope, paracetamol, combinations, active);
        return new ParacetamolFacts(true, orders.counted, ParacetamolRisk.of(surfaced), orders.mayStillBeInUse,
            orders.combinations, combination, acting == null, scope == null,
            item == null && named ? freeTextLimits(paracetamol) : null, orders.notRecognised, false, documented);
    }

    /** CR-03: see the class comment. A dose of another drug is not read; one this cannot place is named. */
    private ParacetamolFacts.Documented documented(UUID patientId, UUID hospitalId, List<MedicationCatalogItem> paracetamol,
                                                   List<MedicationCatalogItem> combinations, List<MedicationCatalogItem> active) {
        if (hospitalId == null || administrations == null) {
            return ParacetamolFacts.Documented.NOT_READ;
        }
        LocalDateTime since = LocalDateTime.now(clock).minus(ROLLING_WINDOW);
        List<ParacetamolFacts.Dose> doses = new java.util.ArrayList<>();
        java.util.Set<String> unreadable = new java.util.LinkedHashSet<>();
        for (MedicationAdministrationRecord given : administrations.findGivenSince(patientId, hospitalId, since)) {
            Prescription rx = given.getPrescription();
            if (isCombination(rx, paracetamol, combinations)) {
                unreadable.add(rx.getMedicationName());
                continue;
            }
            ParacetamolFacts.OtherOrder alone = otherOrder(rx, paracetamol);
            if (alone != null) {
                doses.add(new ParacetamolFacts.Dose(given.getAdministeredAt(), rx.getMedicationName(),
                    readableDose(given, rx), alone.item()));
            } else if (rx.getMedicationCatalogItemId() == null && rx.getMedicationName() != null
                    && !recognised(rx.getMedicationName(), active)) {
                unreadable.add(rx.getMedicationName());
            }
        }
        return new ParacetamolFacts.Documented(true, List.copyOf(doses), List.copyOf(unreadable));
    }

    /** What the row says was given; the order's dose when the row has only "See order". */
    private static String readableDose(MedicationAdministrationRecord given, Prescription rx) {
        String dose = given.getDose();
        return dose == null || dose.isBlank() || !Character.isDigit(dose.trim().charAt(0)) ? rx.getDosage() : dose;
    }

    /** A catalogue item or a cited brand names it, or it carries a class member's INN stem. */
    private static boolean recognised(String name, List<MedicationCatalogItem> active) {
        return AllergyDrugIdentity.of(name, List.of()).resolved()
            || active.stream().anyMatch(i -> OrderedDrugIdentity.agrees(name, i));
    }

    /**
     * QA D1462: the limits a free-text brand is judged against, in mg only. Nothing says which of the
     * drug's items it is, so an item's bands and review age are not read, and the absolute daily
     * maximum, which no override passes, stands only when every item that has one agrees: a syrup's
     * cap never stops a tablet. Not a catalogue row: it is never saved or linked.
     */
    private static MedicationCatalogItem freeTextLimits(List<MedicationCatalogItem> items) {
        List<java.math.BigDecimal> caps = items.stream()
            .map(MedicationCatalogItem::getPediatricMaxDailyMg).filter(Objects::nonNull).toList();
        boolean oneCap = !caps.isEmpty() && caps.stream().allMatch(cap -> cap.compareTo(caps.get(0)) == 0);
        return items.stream()
            .filter(i -> i.getPediatricMaxDailyMg() != null || i.getPediatricMaxDoseMgPerKg() != null
                || i.getPediatricMaxDailyMgPerKg() != null || i.getPediatricMaxSingleDoseMg() != null)
            .findFirst()
            .map(i -> MedicationCatalogItem.builder()
                // CR-01: the route these limits are for, only when every item with limits says the same one.
                .route(oneRoute(items))
                .pediatricMaxDailyMg(oneCap ? caps.get(0) : null)
                .pediatricMaxDoseMgPerKg(i.getPediatricMaxDoseMgPerKg())
                .pediatricMaxDailyMgPerKg(i.getPediatricMaxDailyMgPerKg())
                .pediatricMaxSingleDoseMg(i.getPediatricMaxSingleDoseMg())
                .pediatricDoseSource(i.getPediatricDoseSource())
                .pediatricDoseStatus(i.getPediatricDoseStatus())
                .build())
            .orElse(null);
    }

    private static String oneRoute(List<MedicationCatalogItem> items) {
        List<String> routes = items.stream()
            .filter(i -> i.getPediatricMaxDailyMg() != null || i.getPediatricMaxDoseMgPerKg() != null
                || i.getPediatricMaxDailyMgPerKg() != null || i.getPediatricMaxSingleDoseMg() != null)
            .map(i -> com.example.hms.service.emar.MedicationRoutes.canonical(i.getRoute()))
            .distinct().toList();
        return routes.size() == 1 ? routes.get(0) : null;
    }

    private static boolean namesACombination(String name, List<MedicationCatalogItem> paracetamol,
                                             List<MedicationCatalogItem> combinations) {
        return paracetamol.stream().anyMatch(i -> ParacetamolIdentity.namesItWithAnother(name, i))
            || combinations.stream().anyMatch(i -> ParacetamolIdentity.namesCatalogueItem(name, i)
                || ParacetamolIdentity.namesItWithAnother(name, i));
    }

    /** The orders, by what the total does with them. */
    private record Orders(List<ParacetamolFacts.OtherOrder> counted, List<String> mayStillBeInUse, List<String> combinations,
                          List<String> notRecognised) {
    }

    /** What the order's dates say about it. */
    private enum Running { COUNTED, COUNTED_NO_DURATION, MAY_STILL_BE_IN_USE, ENDED }

    /** This hospital's and the national list's active items. */
    private List<MedicationCatalogItem> activeCatalogue(UUID hospitalId) {
        if (hospitalId == null) {
            return List.of();
        }
        Page<MedicationCatalogItem> page = catalogue.findActivePage(hospitalId, Pageable.unpaged());
        return page == null ? List.of() : page.getContent();
    }

    private Orders otherOrders(UUID patientId, UUID hospitalId, UUID editedOrderId,
                               List<MedicationCatalogItem> paracetamol, List<MedicationCatalogItem> combinations,
                               List<MedicationCatalogItem> active) {
        if (hospitalId == null) {
            return new Orders(List.of(), List.of(), List.of(), List.of());
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
        List<Prescription> unnamed = live.stream()
            .filter(rx -> !combined.contains(rx) && rx.getMedicationCatalogItemId() == null)
            .filter(rx -> alone.stream().noneMatch(other -> other.order() == rx))
            .filter(rx -> rx.getMedicationName() != null && !recognised(rx.getMedicationName(), active))
            .toList();
        if (alone.isEmpty() && combined.isEmpty() && unnamed.isEmpty()) {
            return new Orders(List.of(), List.of(), List.of(), List.of());
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
        List<String> notRecognised = unnamed.stream()
            .filter(rx -> running(rx, closedStays) != Running.ENDED)
            .map(Prescription::getMedicationName)
            .distinct()
            .toList();
        return new Orders(counted, List.copyOf(mayStillBeInUse), combinedNames, notRecognised);
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
            // QA D1223: an older order whose name contradicts its item still counts, on what its dose
            // says in mg: its strength is never read from an item its name does not name.
            MedicationCatalogItem item = linked.get(0);
            boolean strengthIsItsOwn = !OrderedDrugIdentity.contradicts(rx.getMedicationName(), item)
                && !OrderedDrugIdentity.namesOnlyThroughCitedBrand(rx.getMedicationName(), item);
            return new ParacetamolFacts.OtherOrder(rx, strengthIsItsOwn ? item : null);
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
