package com.example.hms.architecture;

import com.example.hms.service.billing.BillableOrderType;
import com.example.hms.service.billing.VisitChargeSource;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * QA D972 guard (NOT_FIXED in rounds 17 and 18): the checkout draft invoice
 * left out a kind of order the visit carried. Round 17 billed lab and
 * procedure orders listed by hand and missed imaging; nothing said the list
 * was complete. This fails when
 * <ul>
 *   <li>a {@link BillableOrderType} charged on the visit invoice has no
 *       {@link VisitChargeSource}, or more than one;</li>
 *   <li>an entity written on a visit (it links to an encounter) is neither
 *       the source of a registry entry nor declared not billable here, with
 *       the reason — a new order type cannot reach checkout unbilled.</li>
 * </ul>
 */
@DisplayName("Every billable order type of a visit has a charge mapping (QA D972)")
class BillableOrderRegistryGuardTest {

    private static final Path MODEL = Paths.get("src/main/java/com/example/hms/model");
    private static final Pattern ENCOUNTER_LINK = Pattern.compile(
        "private\\s+(?:Encounter\\s+encounter|(?:java\\.util\\.)?UUID\\s+encounterId)\\s*;");
    private static final Pattern ENTITY = Pattern.compile("(?m)^@Entity\\b");

    /** Entities linked to a visit that are not a charge, each with why. Shrink only. */
    private static final Map<String, String> NOT_BILLABLE = Map.ofEntries(
        Map.entry("BillingInvoice", "the bill itself"),
        Map.entry("Consultation", "an in-house opinion between clinicians on the visit; a hospital that charges "
            + "for it records it as a service on the visit (EncounterTreatment, billed under CONSULTATION)"),
        Map.entry("DeathRecord", "a record, not a service"),
        Map.entry("DischargeSummary", "documentation"),
        Map.entry("EncounterHistory", "audit trail"),
        Map.entry("EncounterNote", "documentation"),
        Map.entry("EncounterNoteHistory", "audit trail"),
        Map.entry("InBasketItem", "a staff message"),
        Map.entry("ObgynReferral", "a referral out: the receiving facility bills its own care"),
        Map.entry("PatientImmunization", "a vaccination record; a dose the hospital charges for is a recorded "
            + "service (CONSULTATION) or sold by the pharmacy (PHARMACY_DISPENSE)"),
        Map.entry("PatientRecall", "a reminder to come back"),
        Map.entry("PatientTreatmentConsent", "a consent form"),
        Map.entry("TreatmentPlan", "documentation"),
        Map.entry("VisitEducationDocumentation", "documentation"));

    @Test
    @DisplayName("each visit-invoice type has exactly one charge source")
    void everyVisitInvoiceTypeHasOneSource() throws Exception {
        Map<BillableOrderType, List<String>> sources = new EnumMap<>(BillableOrderType.class);
        for (VisitChargeSource source : allSources()) {
            sources.computeIfAbsent(source.type(), t -> new ArrayList<>()).add(source.getClass().getSimpleName());
        }
        assertThat(sources).as("the scan found the charge sources").containsKey(BillableOrderType.IMAGING_ORDER);
        List<String> problems = new ArrayList<>();
        for (BillableOrderType type : BillableOrderType.values()) {
            List<String> found = sources.getOrDefault(type, List.of());
            if (type.onVisitInvoice() && found.size() != 1) {
                problems.add(type + " has " + found.size() + " charge source(s) " + found);
            }
            if (!type.onVisitInvoice() && !found.isEmpty()) {
                problems.add(type + " is charged elsewhere (" + type.route() + ") but has a visit source " + found);
            }
        }
        assertThat(problems).as("a billable order type without exactly one charge mapping (QA D972)"
            + SourceScan.MANDATE).isEmpty();
    }

    @Test
    @DisplayName("every entity written on a visit is billable through the registry or declared not billable")
    void everyVisitEntityIsMapped() throws IOException {
        Set<String> covered = new TreeSet<>();
        for (BillableOrderType type : BillableOrderType.values()) {
            type.entities().forEach(entity -> covered.add(entity.getSimpleName()));
        }
        Set<String> visitEntities = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MODEL)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (ENTITY.matcher(source).find() && ENCOUNTER_LINK.matcher(source).find()) {
                    visitEntities.add(file.getFileName().toString().replace(".java", ""));
                }
            }
        }
        assertThat(visitEntities).as("the scan found the visit's orders").contains("ImagingOrder", "LabOrder");
        List<String> unmapped = visitEntities.stream()
            .filter(entity -> !covered.contains(entity) && !NOT_BILLABLE.containsKey(entity))
            .toList();
        assertThat(unmapped).as("an entity written on a visit with no charge mapping: add it to BillableOrderType "
            + "with a VisitChargeSource, or to NOT_BILLABLE with the reason (QA D972)" + SourceScan.MANDATE).isEmpty();
        List<String> stale = NOT_BILLABLE.keySet().stream()
            .filter(entity -> !visitEntities.contains(entity) || covered.contains(entity))
            .toList();
        assertThat(stale).as("NOT_BILLABLE names an entity that is no longer on a visit, or is now billed").isEmpty();
    }

    /** Billing-money plan PR 3: where an invoice charge outside the visit sources is priced. */
    private static final Map<BillableOrderType.Route, Path> ROUTE_CHARGER = Map.of(
        BillableOrderType.Route.DELIVERY_CHARGE,
        Paths.get("src/main/java/com/example/hms/service/DeliveryChargeService.java"));
    private static final Path BILLING = Paths.get("src/main/java/com/example/hms/service/billing");

    @Test
    @DisplayName("PR 3: every type charged on an invoice declares its price-list link, and its charge code looks it up")
    void everyInvoicedTypeIsPricedThroughItsLink() throws Exception {
        Map<BillableOrderType, String> sourceFiles = new EnumMap<>(BillableOrderType.class);
        for (VisitChargeSource source : allSources()) {
            sourceFiles.put(source.type(), source.getClass().getSimpleName());
        }
        List<String> problems = new ArrayList<>();
        for (BillableOrderType type : BillableOrderType.values()) {
            if (type.route() == BillableOrderType.Route.PHARMACY_CHECKOUT) {
                if (!type.linkTypes().isEmpty()) {
                    problems.add(type + " is priced at the pharmacy but declares price-list links " + type.linkTypes());
                }
                continue;
            }
            if (type.linkTypes().isEmpty()) {
                problems.add(type + " is charged on an invoice but declares no price-list link type");
                continue;
            }
            Path file = type.onVisitInvoice()
                ? BILLING.resolve(sourceFiles.get(type) + ".java")
                : ROUTE_CHARGER.get(type.route());
            if (file == null || !Files.exists(file)) {
                problems.add(type + " has no charge code to check (" + file + ")");
                continue;
            }
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            String lookup = type.onVisitInvoice() ? "visit.tariff(" : "tariffResolver.resolve(";
            if (!code.contains(lookup)) {
                problems.add(type + " (" + file.getFileName() + ") never looks up its tariff with " + lookup);
            }
            for (com.example.hms.enums.PriceListLinkType link : type.linkTypes()) {
                if (!code.contains("PriceListLinkType." + link.name())) {
                    problems.add(type + " (" + file.getFileName() + ") declares " + link + " but never looks it up");
                }
            }
        }
        assertThat(problems).as("a billable type the price list cannot price (billing-money plan PR 3)"
            + SourceScan.MANDATE).isEmpty();
    }

    /** Every VisitChargeSource of production code, built with mocked dependencies to read its type. */
    static List<VisitChargeSource> allSources() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(VisitChargeSource.class));
        List<VisitChargeSource> sources = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.example.hms")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            if (type.isInterface() || java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            Constructor<?> constructor = type.getDeclaredConstructors()[0];
            Object[] args = new Object[constructor.getParameterCount()];
            for (int i = 0; i < args.length; i++) {
                args[i] = mock(constructor.getParameterTypes()[i]);
            }
            constructor.setAccessible(true);
            sources.add((VisitChargeSource) constructor.newInstance(args));
        }
        return sources;
    }
}
