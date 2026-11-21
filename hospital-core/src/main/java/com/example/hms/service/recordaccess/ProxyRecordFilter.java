package com.example.hms.service.recordaccess;

import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.Appointment;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Encounter;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestComponent;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientImmunization;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.model.PriceListItem;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.ProxySensitiveReleaseRepository;
import com.example.hms.service.pharmacy.DispenseVerificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * QA D2730 (r65, High): the one rule for what a proxy (a caregiver holding a
 * family-access grant) may read of the grantor's record.
 *
 * <p><b>A proxy never receives a row whose effective sensitivity category is
 * set</b>, with the exceptions the 2026-11-20 policy names and
 * {@link ProxySensitivityPolicy} holds (provisional): a tuberculosis row for a
 * grant that names that scope, a child's record for a verified parent or
 * guardian, and a minor's result a clinician released to that parent. The
 * category is the codebase's existing one ({@link SensitivityClassifier}): the
 * row's explicit tag, else its visit's, else the department default. A lab
 * result also carries its test's tag, when the hospital set one; a panel
 * carries the tag of any of its components; a prescription carries its
 * medicine's; a problem follows the visit it was recorded in; a vital sign,
 * which names no visit, follows the sensitive visit it was taken during; an
 * invoice follows its visit and every test it bills.
 *
 * <p>Who is reading comes from the grant check, through {@link ProxyReadScope}.
 * With no grant in hand the filter admits untagged rows only, and an exception
 * applies only to a row of the patient that grant names. The patient's own
 * reads never come through here.
 *
 * <p>Every proxy read applies this filter; {@code ProxyReadSensitivityGuardTest}
 * fails when a new one does not.
 */
@Component
public class ProxyRecordFilter {

    /** A withdrawn order is on no invoice, so it cannot make one sensitive. */
    private static final Set<LabOrderStatus> NOT_BILLED =
        EnumSet.of(LabOrderStatus.CANCELLED, LabOrderStatus.ENTERED_IN_ERROR);

    private final SensitivityClassifier sensitivityClassifier;
    private final LabTestDefinitionRepository labTestDefinitionRepository;
    private final ProxyReadScope proxyReadScope;
    private final ProxySensitiveReleaseRepository releaseRepository;
    private final MedicationCatalogItemRepository medicationCatalogItemRepository;
    private final LabOrderRepository labOrderRepository;
    private final PriceListItemRepository priceListItemRepository;

    @Autowired
    public ProxyRecordFilter(SensitivityClassifier sensitivityClassifier,
                             LabTestDefinitionRepository labTestDefinitionRepository,
                             ProxyReadScope proxyReadScope,
                             ProxySensitiveReleaseRepository releaseRepository,
                             MedicationCatalogItemRepository medicationCatalogItemRepository,
                             LabOrderRepository labOrderRepository,
                             PriceListItemRepository priceListItemRepository) {
        this.sensitivityClassifier = sensitivityClassifier;
        this.labTestDefinitionRepository = labTestDefinitionRepository;
        this.proxyReadScope = proxyReadScope;
        this.releaseRepository = releaseRepository;
        this.medicationCatalogItemRepository = medicationCatalogItemRepository;
        this.labOrderRepository = labOrderRepository;
        this.priceListItemRepository = priceListItemRepository;
    }

    /**
     * The filter with no grant in hand and no catalogue behind it: untagged
     * rows only, by the visit and the test. What a unit test of one read builds.
     */
    public ProxyRecordFilter(SensitivityClassifier sensitivityClassifier,
                             LabTestDefinitionRepository labTestDefinitionRepository) {
        this(sensitivityClassifier, labTestDefinitionRepository, new ProxyReadScope(), null, null, null, null);
    }

    /** What one test's live definition says: whether there is one, and its tag. */
    private record LiveTestTag(boolean hasLiveVersion, SensitivityCategory category) {
    }

    /** What one read resolves once, however many rows it filters. */
    private static final class ReadCache {
        private final Map<String, LiveTestTag> testTags = new HashMap<>();
        private final Map<String, Map<String, SensitivityCategory>> taggedCodes = new HashMap<>();
        private Set<UUID> releasedOrders;
    }

    /** The strict rule, for a row with no patient to judge it by: only an untagged row reaches a proxy. */
    public boolean admits(SensitivityCategory effectiveCategory) {
        return effectiveCategory == null;
    }

    /**
     * The rule for a row of a known patient: untagged, or one of the policy's
     * exceptions for the grant of this read ({@link ProxySensitivityPolicy}).
     * An exception never applies to another patient's row than the one the
     * grant names.
     */
    private boolean admits(SensitivityCategory category, Patient subject, Hospital recordedAt) {
        if (category == null) {
            return true;
        }
        ProxyReadContext reader = readerOf(subject);
        if (reader == null) {
            return false;
        }
        Integer configured = recordedAt == null ? null : recordedAt.getAdolescentConfidentialityAge();
        return ProxySensitivityPolicy.admits(category, ageOf(subject),
            ProxySensitivityPolicy.adolescentAgeOr(configured), reader);
    }

    /** The grant of this read, when it is a grant on this patient's record. */
    private ProxyReadContext readerOf(Patient subject) {
        if (subject == null || subject.getId() == null) {
            return null;
        }
        return proxyReadScope.current()
            .filter(reader -> subject.getId().equals(reader.grantorPatientId()))
            .orElse(null);
    }

    private static Integer ageOf(Patient subject) {
        return subject == null ? null : ProxySensitivityPolicy.ageOn(subject.getDateOfBirth(),
            LocalDate.now(java.time.ZoneId.systemDefault()));
    }

    /** A visit, by its explicit tag or its department's default. */
    public boolean admits(Encounter encounter) {
        if (encounter == null) {
            return true;
        }
        return admits(sensitivityClassifier.effectiveCategory(encounter), encounter.getPatient(),
            encounter.getHospital());
    }

    /** A result inherits its order's visit and its test's tag. */
    public boolean admits(LabResult result) {
        return result == null || admits(result.getLabOrder());
    }

    public boolean admits(LabOrder order) {
        return admits(order, new ReadCache());
    }

    /**
     * One read's filter over lab results. The tag of each test, and the
     * results released to this grant, are resolved once for the whole read,
     * however many results the patient has.
     */
    public Predicate<LabResult> labResults() {
        ReadCache cache = new ReadCache();
        return result -> result == null || admits(result.getLabOrder(), cache);
    }

    /**
     * An order by its visit and by its test. Policy 2026-11-20: a minor's
     * result a clinician released to this parent is read whatever hid it -
     * that one order, not the test's other results.
     */
    private boolean admits(LabOrder order, ReadCache cache) {
        if (order == null) {
            return true;
        }
        Hospital recordedAt = order.getHospital();
        SensitivityCategory visitCategory = sensitivityClassifier.effectiveCategory(order.getEncounter());
        SensitivityCategory testCategory = testCategory(order.getLabTestDefinition(), recordedAt, cache);
        if (visitCategory == null && testCategory == null) {
            return true;
        }
        Patient subject = order.getPatient();
        // HIV only, judged by the test's own category first, and never a result that violence is on: a
        // release of anything else (a pregnancy test of an HIV-clinic visit) is not honoured.
        if (ProxySensitivityPolicy.releasable(visitCategory, testCategory)
                && releasedToReader(order, subject, cache)) {
            return true;
        }
        return admits(visitCategory, subject, recordedAt) && admits(testCategory, subject, recordedAt);
    }

    /**
     * What hides an order's result from the family, as the clinician's release
     * screen shows it: the category, whether a clinician may release it, and
     * (r66 D2903) whether violence on the visit or the test is what forbids it.
     */
    public record FamilyHiding(SensitivityCategory category, boolean releasable, boolean violence) {

        /** A hiding that violence is not part of. */
        public FamilyHiding(SensitivityCategory category, boolean releasable) {
            this(category, releasable, false);
        }
    }

    /**
     * What a <em>verified parent or guardian</em> of this patient is refused of
     * an order's result, or null when they read it (a child's sickle status, a
     * result of a child under the hospital's adolescent age): the same rule the
     * proxy read applies ({@link ProxySensitivityPolicy#hiddenFromVerifiedParent}),
     * so the screen never lists as hidden what the parent already sees. The
     * category shown is the test's own when it hides the result, else the
     * visit's: a pregnancy test of an HIV-clinic visit is listed as pregnancy,
     * not as an HIV result, and is not releasable.
     */
    public FamilyHiding familyHidingOf(LabOrder order) {
        if (order == null) {
            return null;
        }
        Hospital recordedAt = order.getHospital();
        SensitivityCategory visitCategory = sensitivityClassifier.effectiveCategory(order.getEncounter());
        SensitivityCategory testCategory = testCategory(order.getLabTestDefinition(), recordedAt, new ReadCache());
        Integer age = ageOf(order.getPatient());
        int adolescentAge = ProxySensitivityPolicy.adolescentAgeOr(
            recordedAt == null ? null : recordedAt.getAdolescentConfidentialityAge());
        boolean byVisit = ProxySensitivityPolicy.hiddenFromVerifiedParent(visitCategory, age, adolescentAge);
        boolean byTest = ProxySensitivityPolicy.hiddenFromVerifiedParent(testCategory, age, adolescentAge);
        if (!byVisit && !byTest) {
            return null;
        }
        SensitivityCategory shown = byTest ? testCategory : visitCategory;
        return new FamilyHiding(shown, ProxySensitivityPolicy.releasable(visitCategory, testCategory),
            ProxySensitivityPolicy.neverReleased(visitCategory, testCategory));
    }

    private boolean releasedToReader(LabOrder order, Patient subject, ReadCache cache) {
        ProxyReadContext reader = readerOf(subject);
        if (releaseRepository == null || order.getId() == null
                || !ProxySensitivityPolicy.honoursReleases(ageOf(subject), reader)) {
            return false;
        }
        if (cache.releasedOrders == null) {
            Set<UUID> released = releaseRepository.findReleasedLabOrderIds(reader.grantId());
            cache.releasedOrders = released == null ? Set.of() : released;
        }
        return cache.releasedOrders.contains(order.getId());
    }

    /**
     * The category of a test, across its versions. A new version retires the
     * definition it replaces, and a retired definition is read-only: read on
     * the exact row an order used, the results of version 1 stayed visible
     * after version 2 was tagged. So the test's <em>live</em> definition
     * decides - the ACTIVE one with the same code in the same catalogue (the
     * hospital's, or the platform's) - for every result of that test, old and
     * new. A test with no live definition left keeps the tag of the row the
     * order used.
     */
    private SensitivityCategory testCategory(LabTestDefinition definition, Hospital orderedAt, ReadCache cache) {
        if (definition == null) {
            return null;
        }
        Hospital catalogue = definition.getHospital();
        UUID catalogueId = catalogue == null ? null : catalogue.getId();
        SensitivityCategory own = ownOrLiveCategory(definition, catalogueId, cache);
        if (own != null) {
            return own;
        }
        // Review of #1242: a platform panel (no hospital of its own) ordered at a hospital is judged by
        // THAT hospital's tagged tests too; read from the definition alone, the hospital's tag on a
        // component was ignored.
        UUID orderedAtId = orderedAt == null ? null : orderedAt.getId();
        return componentCategory(definition, orderedAtId != null ? orderedAtId : catalogueId, cache);
    }

    private SensitivityCategory ownOrLiveCategory(LabTestDefinition definition, UUID hospitalId, ReadCache cache) {
        String code = definition.getTestCode();
        if (code == null || code.isBlank()) {
            return definition.getSensitivityCategory();
        }
        LiveTestTag live = cache.testTags.computeIfAbsent(hospitalId + "|" + normalised(code),
            key -> liveTagOf(code.trim(), hospitalId));
        return live.hasLiveVersion() ? live.category() : definition.getSensitivityCategory();
    }

    /**
     * Policy 2026-11-20, combined panels: a panel is one order, one result
     * and one name (« Bilan prénatal »), so its values cannot be shown one by
     * one without showing which were left out. The safe option is the whole
     * panel: it is withheld as soon as one of its components is a test the
     * hospital (or the platform catalogue) tagged, matched by code. A panel
     * the lab director tagged himself is withheld by its own tag, above.
     */
    private SensitivityCategory componentCategory(LabTestDefinition panel, UUID hospitalId, ReadCache cache) {
        List<LabTestComponent> components = panel.getComponents();
        if (components == null || components.isEmpty()) {
            return null;
        }
        Map<String, SensitivityCategory> tagged = cache.taggedCodes.computeIfAbsent(String.valueOf(hospitalId),
            key -> taggedCodesOf(hospitalId));
        return components.stream()
            .map(LabTestComponent::getCode)
            .filter(code -> code != null && !code.isBlank())
            .map(code -> tagged.get(normalised(code)))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    /** The live tagged tests a panel of this catalogue can contain: the hospital's, and the platform's. */
    private Map<String, SensitivityCategory> taggedCodesOf(UUID hospitalId) {
        Map<String, SensitivityCategory> byCode = new HashMap<>();
        addTagged(byCode, labTestDefinitionRepository.findLiveTaggedGlobal());
        if (hospitalId != null) {
            addTagged(byCode, labTestDefinitionRepository.findLiveTaggedAtHospital(hospitalId));
        }
        return byCode;
    }

    private static void addTagged(Map<String, SensitivityCategory> byCode, List<LabTestDefinition> definitions) {
        if (definitions == null) {
            return;
        }
        for (LabTestDefinition definition : definitions) {
            if (definition.getTestCode() != null && definition.getSensitivityCategory() != null) {
                byCode.put(normalised(definition.getTestCode()), definition.getSensitivityCategory());
            }
        }
    }

    private static String normalised(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private LiveTestTag liveTagOf(String code, UUID hospitalId) {
        List<LabTestDefinition> versions = hospitalId == null
            ? labTestDefinitionRepository.findLiveGlobalByCode(code)
            : labTestDefinitionRepository.findLiveByCodeAtHospital(code, hospitalId);
        List<LabTestDefinition> live = versions == null ? List.of() : versions.stream()
            .filter(version -> version.getApprovalStatus() == LabTestDefinitionApprovalStatus.ACTIVE)
            .toList();
        return new LiveTestTag(!live.isEmpty(), live.stream()
            .map(LabTestDefinition::getSensitivityCategory)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null));
    }

    /**
     * A prescription written in a sensitive visit is sensitive; so is one for
     * a medicine the hospital tagged (policy 2026-11-20: an antiretroviral
     * prescribed in a general consultation names HIV by itself).
     */
    public boolean admits(Prescription prescription) {
        if (prescription == null) {
            return true;
        }
        if (!admits(prescription.getEncounter())) {
            return false;
        }
        return admits(medicineCategory(prescription), prescription.getPatient(), prescription.getHospital());
    }

    private SensitivityCategory medicineCategory(Prescription prescription) {
        if (medicationCatalogItemRepository == null) {
            return null;
        }
        UUID itemId = prescription.getMedicationCatalogItemId();
        if (itemId == null) {
            return unlinkedMedicineCategory(prescription);
        }
        return medicationCatalogItemRepository.findById(itemId)
            .map(MedicationCatalogItem::getSensitivityCategory)
            .orElse(null);
    }

    /**
     * Review of #1242: an order with no catalogue link. The prescription
     * service leaves an order unlinked when its name matches several items
     * (two strengths of dolutegravir), so "no link" never meant "not a tagged
     * medicine". It is compared, at its own hospital, with the tagged items of
     * the catalogue: by its code, then by the name as typed (the pharmacy's
     * one name rule). ANY tagged match withholds it - a wrong guess here
     * hides a prescription, never shows one.
     */
    private SensitivityCategory unlinkedMedicineCategory(Prescription prescription) {
        Hospital hospital = prescription.getHospital();
        UUID hospitalId = hospital == null ? null : hospital.getId();
        if (hospitalId == null) {
            return null;
        }
        List<MedicationCatalogItem> tagged = medicationCatalogItemRepository.findTaggedVisibleAt(hospitalId);
        if (tagged == null || tagged.isEmpty()) {
            return null;
        }
        String code = prescription.getMedicationCode();
        for (MedicationCatalogItem item : tagged) {
            boolean byCode = code != null && !code.isBlank() && item.getCode() != null
                && item.getCode().trim().equalsIgnoreCase(code.trim());
            if (byCode || DispenseVerificationService.namesCatalogItem(prescription.getMedicationName(), item)
                    || DispenseVerificationService.namesCatalogItem(prescription.getMedicationDisplayName(), item)) {
                return item.getSensitivityCategory();
            }
        }
        return null;
    }

    /** A problem by its own tag, else by the visit it was recorded in. */
    public boolean admits(PatientProblem problem) {
        if (problem == null) {
            return true;
        }
        return admits(sensitivityClassifier.effectiveCategory(problem), problem.getPatient(), problem.getHospital());
    }

    public boolean admits(PatientImmunization immunization) {
        return immunization == null || admits(immunization.getEncounter());
    }

    /**
     * An invoice names the visit's services; it follows its visit. Policy
     * 2026-11-20 (Loi 030-2008 art. 14: financial information too): it also
     * follows every test it bills - a line « Sérologie VIH » on the invoice
     * of a general consultation says what the hidden result is about. The
     * whole invoice is withheld, not the line: an invoice with a line missing
     * no longer adds up, and says so.
     */
    public boolean admits(BillingInvoice invoice) {
        if (invoice == null) {
            return true;
        }
        Encounter visit = invoice.getEncounter();
        if (!admits(visit)) {
            return false;
        }
        ReadCache cache = new ReadCache();
        return billsNoHiddenOrder(visit, cache) && billsNoHiddenTest(invoice, cache);
    }

    /** The tests ordered in the invoice's visit are what its lines charge. */
    private boolean billsNoHiddenOrder(Encounter visit, ReadCache cache) {
        if (visit == null || visit.getId() == null || labOrderRepository == null) {
            return true;
        }
        List<UUID> ids = labOrderRepository.findIdsByEncounterIdAndStatusNotIn(visit.getId(), NOT_BILLED);
        if (ids == null || ids.isEmpty()) {
            return true;
        }
        return labOrderRepository.findAllById(ids).stream().allMatch(order -> admits(order, cache));
    }

    /** A line priced from the price list names the test its price is linked to, with or without a visit. */
    private boolean billsNoHiddenTest(BillingInvoice invoice, ReadCache cache) {
        if (priceListItemRepository == null || invoice.getInvoiceItems() == null) {
            return true;
        }
        List<UUID> priced = invoice.getInvoiceItems().stream()
            .map(InvoiceItem::getPriceListItemId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (priced.isEmpty()) {
            return true;
        }
        // Review of #1242: read at the INVOICE's hospital, outside the caller's tenant scope. The scoped
        // findAllById answered with the proxy's own hospitals: at a hospital where the proxy holds no
        // assignment it loaded nothing, and the invoice with the hidden test was shown (QA D1212 trap).
        Hospital billedAt = invoice.getHospital();
        UUID billedAtId = billedAt == null ? null : billedAt.getId();
        List<PriceListItem> items = billedAtId == null ? List.of()
            : priceListItemRepository.findUnscopedByHospitalIdAndIdIn(billedAtId, priced);
        if (items == null || items.size() < priced.size()) {
            // A priced line whose item cannot be read cannot be judged: withheld.
            return false;
        }
        for (PriceListItem item : items) {
            if (!admitsPricedFrom(item, invoice, cache)) {
                return false;
            }
        }
        return true;
    }

    /** One price-list item: an ordinary one, or the price of a test this proxy may read. */
    private boolean admitsPricedFrom(PriceListItem item, BillingInvoice invoice, ReadCache cache) {
        if (item.getLinkType() != PriceListLinkType.LAB_TEST_DEFINITION || item.getLinkKey() == null) {
            return true;
        }
        UUID definitionId = uuidOrNull(item.getLinkKey());
        LabTestDefinition definition = definitionId == null ? null
            : labTestDefinitionRepository.findById(definitionId).orElse(null);
        return admits(testCategory(definition, invoice.getHospital(), cache), invoice.getPatient(),
            invoice.getHospital());
    }

    private static UUID uuidOrNull(String text) {
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    /**
     * An appointment reveals its department, so a department that defaults to
     * a sensitive category withholds it. The visit it opened, when there is
     * one, is judged by the caller (it is the owning side of the link).
     */
    public boolean admits(Appointment appointment) {
        return appointment == null
            || admits(sensitivityClassifier.effectiveCategory(null, appointment.getDepartment()),
                appointment.getPatient(), appointment.getHospital());
    }

    /** Of a patient's visits, the ones a proxy may not read. */
    public List<Encounter> sensitiveAmong(Collection<Encounter> visits) {
        return visits == null ? List.of() : visits.stream().filter(visit -> !admits(visit)).toList();
    }

    /**
     * A vital sign names no visit, so it cannot carry one's tag. It is
     * withheld when it was taken at the hospital of a sensitive visit, on a
     * day that visit covers (its first day to its checkout day; to today for
     * a visit still open): a weight or a blood pressure taken in the HIV
     * clinic that morning says the patient was there.
     *
     * @param sensitiveVisits the patient's sensitive visits ({@link #sensitiveAmong})
     */
    public boolean admits(PatientVitalSign vital, Collection<Encounter> sensitiveVisits) {
        if (vital == null || sensitiveVisits == null || sensitiveVisits.isEmpty()) {
            return true;
        }
        LocalDateTime recordedAt = vital.getRecordedAt();
        if (recordedAt == null) {
            // No time to place it by: withheld, since the patient has sensitive visits.
            return false;
        }
        LocalDate day = recordedAt.toLocalDate();
        UUID vitalHospitalId = vital.getHospital() == null ? null : vital.getHospital().getId();
        return sensitiveVisits.stream().noneMatch(visit -> sameHospital(vitalHospitalId, visit) && covers(visit, day));
    }

    private static boolean sameHospital(UUID vitalHospitalId, Encounter visit) {
        UUID visitHospitalId = visit.getHospital() == null ? null : visit.getHospital().getId();
        // An unknown hospital on either side cannot rule the visit out.
        return vitalHospitalId == null || visitHospitalId == null || vitalHospitalId.equals(visitHospitalId);
    }

    private static boolean covers(Encounter visit, LocalDate day) {
        if (visit.getEncounterDate() == null) {
            return true;
        }
        LocalDate first = visit.getEncounterDate().toLocalDate();
        LocalDate last;
        if (visit.getCheckoutTimestamp() != null) {
            last = visit.getCheckoutTimestamp().toLocalDate();
        } else if (visit.getStatus() != null && visit.getStatus().isClosed()) {
            last = first;
        } else {
            last = LocalDate.now(java.time.ZoneId.systemDefault());
        }
        LocalDate end = last.compareTo(first) < 0 ? first : last;
        return day.compareTo(first) >= 0 && day.compareTo(end) <= 0;
    }
}
