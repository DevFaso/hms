package com.example.hms.service.recordaccess;

import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.enums.ProxyRelationship;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestComponent;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.Prescription;
import com.example.hms.model.PriceListItem;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.ProxySensitiveReleaseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Policy 2026-11-20 (provisional): the proxy read filter with a verified
 * grant in hand - the categories added, the records around a hidden item, a
 * combined panel, and the release of a minor's result.
 */
@DisplayName("Policy 2026-11-20: the proxy filter, the surrounding records and a minor's released result")
class ProxyRecordFilterPolicyTest {

    private final LabTestDefinitionRepository definitions = mock(LabTestDefinitionRepository.class);
    private final ProxySensitiveReleaseRepository releases = mock(ProxySensitiveReleaseRepository.class);
    private final MedicationCatalogItemRepository medicines = mock(MedicationCatalogItemRepository.class);
    private final LabOrderRepository labOrders = mock(LabOrderRepository.class);
    private final PriceListItemRepository priceItems = mock(PriceListItemRepository.class);
    private final ProxyReadScope scope = new ProxyReadScope();
    private final ProxyRecordFilter filter = new ProxyRecordFilter(new SensitivityClassifierImpl(), definitions,
        scope, releases, medicines, labOrders, priceItems);

    private final Hospital hospital = new Hospital();
    private final Patient adult = patient(40);
    private final Patient teenager = patient(15);
    private final Patient child = patient(6);
    private final UUID grantId = UUID.randomUUID();

    @BeforeEach
    void openARequest() {
        hospital.setId(UUID.randomUUID());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void closeTheRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static Patient patient(int age) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setDateOfBirth(LocalDate.now().minusYears(age).minusDays(1));
        return patient;
    }

    /** The grant check ran: this grant, on this patient's record. */
    private void readingAs(Patient grantor, ProxyRelationship relationship, boolean verified, String... scopes) {
        scope.enter(new ProxyReadContext(grantId, grantor.getId(), relationship, verified, Set.of(scopes)));
    }

    private Encounter visit(Patient patient, SensitivityCategory tag) {
        Encounter visit = new Encounter();
        visit.setId(UUID.randomUUID());
        visit.setPatient(patient);
        visit.setHospital(hospital);
        visit.setSensitivityCategory(tag);
        return visit;
    }

    private LabTestDefinition test(String code, SensitivityCategory tag) {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setId(UUID.randomUUID());
        definition.setTestCode(code);
        definition.setName(code);
        definition.setHospital(hospital);
        definition.setSensitivityCategory(tag);
        definition.setApprovalStatus(LabTestDefinitionApprovalStatus.ACTIVE);
        when(definitions.findLiveByCodeAtHospital(code, hospital.getId())).thenReturn(List.of(definition));
        return definition;
    }

    private LabOrder order(Patient patient, Encounter visit, LabTestDefinition definition) {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setPatient(patient);
        order.setHospital(hospital);
        order.setEncounter(visit);
        order.setLabTestDefinition(definition);
        return order;
    }

    private static LabResult resultOf(LabOrder order) {
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setReleased(true);
        return result;
    }

    @Nested
    @DisplayName("the default categories")
    class Categories {

        @Test
        @DisplayName("an adult's HIV, STI, hepatitis, pregnancy, violence, mental-health, substance-use and genetic rows are hidden, whatever the grant")
        void anAdultsSensitiveRowsAreHidden() {
            readingAs(adult, ProxyRelationship.SPOUSE, true, "ALL");
            for (SensitivityCategory category : SensitivityCategory.values()) {
                assertThat(filter.admits(visit(adult, category))).as("visit %s", category).isFalse();
                PatientProblem problem = new PatientProblem();
                problem.setPatient(adult);
                problem.setHospital(hospital);
                problem.setSensitivityCategory(category);
                assertThat(filter.admits(problem)).as("problem %s", category).isFalse();
            }
            assertThat(filter.admits(visit(adult, null))).isTrue();
        }

        @Test
        @DisplayName("tuberculosis is restricted: read by the grant that names the scope, hidden from « tout »")
        void tuberculosisIsRestricted() {
            Encounter tbVisit = visit(adult, SensitivityCategory.TUBERCULOSIS);
            readingAs(adult, ProxyRelationship.SPOUSE, false, "ALL");
            assertThat(filter.admits(tbVisit)).isFalse();

            readingAs(adult, ProxyRelationship.SPOUSE, false, "VIEW_RECORDS", ProxyReadContext.SCOPE_TUBERCULOSIS);
            assertThat(filter.admits(tbVisit)).isTrue();
            assertThat(filter.admits(visit(adult, SensitivityCategory.HIV))).isFalse();
        }

        @Test
        @DisplayName("an adult's sickle genotype is hidden; a child's own stays visible to the verified parent")
        void sickleGenotype() {
            LabTestDefinition electrophoresis = test("EPHB", SensitivityCategory.SICKLE_CELL);
            readingAs(adult, ProxyRelationship.SPOUSE, true, "ALL");
            assertThat(filter.admits(order(adult, visit(adult, null), electrophoresis))).isFalse();

            readingAs(child, ProxyRelationship.PARENT, true, "ALL");
            assertThat(filter.admits(order(child, visit(child, null), electrophoresis))).isTrue();

            readingAs(child, ProxyRelationship.PARENT, false, "ALL");
            assertThat(filter.admits(order(child, visit(child, null), electrophoresis)))
                .as("a parent the hospital has not verified").isFalse();
        }

        @Test
        @DisplayName("an exception never applies to another patient than the one the grant names")
        void theGrantNamesOnePatient() {
            readingAs(child, ProxyRelationship.PARENT, true, "ALL", ProxyReadContext.SCOPE_TUBERCULOSIS);
            assertThat(filter.admits(visit(child, SensitivityCategory.TUBERCULOSIS))).isTrue();
            assertThat(filter.admits(visit(adult, SensitivityCategory.TUBERCULOSIS))).isFalse();
        }

        @Test
        @DisplayName("the hospital's adolescent age decides the other categories of a minor: 12 by default, its own when set")
        void theAdolescentAge() {
            readingAs(teenager, ProxyRelationship.PARENT, true, "ALL");
            Encounter mentalHealth = visit(teenager, SensitivityCategory.BEHAVIOURAL_HEALTH);
            assertThat(filter.admits(mentalHealth)).as("15, default 12").isFalse();
            hospital.setAdolescentConfidentialityAge(16);
            assertThat(filter.admits(mentalHealth)).as("15, the hospital says 16").isTrue();
            hospital.setAdolescentConfidentialityAge(null);

            readingAs(child, ProxyRelationship.PARENT, true, "ALL");
            assertThat(filter.admits(visit(child, SensitivityCategory.VIRAL_HEPATITIS))).as("6, default 12").isTrue();
            assertThat(filter.admits(visit(child, SensitivityCategory.HIV))).as("HIV at 6").isFalse();
        }
    }

    @Nested
    @DisplayName("the records around a hidden item")
    class SurroundingRecords {

        @Test
        @DisplayName("a prescription of a tagged medicine is hidden, whatever visit wrote it")
        void aTaggedMedicine() {
            readingAs(adult, ProxyRelationship.SPOUSE, true, "ALL");
            UUID arv = UUID.randomUUID();
            when(medicines.findById(arv)).thenReturn(Optional.of(
                MedicationCatalogItem.builder().sensitivityCategory(SensitivityCategory.HIV).build()));
            UUID paracetamol = UUID.randomUUID();
            when(medicines.findById(paracetamol)).thenReturn(Optional.of(MedicationCatalogItem.builder().build()));

            assertThat(filter.admits(prescription(arv))).isFalse();
            assertThat(filter.admits(prescription(paracetamol))).isTrue();
            assertThat(filter.admits(prescription(null))).isTrue();
        }

        private Prescription prescription(UUID catalogItemId) {
            Prescription prescription = new Prescription();
            prescription.setPatient(adult);
            prescription.setHospital(hospital);
            prescription.setEncounter(visit(adult, null));
            prescription.setMedicationCatalogItemId(catalogItemId);
            return prescription;
        }

        @Test
        @DisplayName("the invoice of a general visit that bills a tagged test is hidden whole")
        void anInvoiceThatBillsATaggedTest() {
            readingAs(adult, ProxyRelationship.SPOUSE, true, "ALL");
            Encounter general = visit(adult, null);
            LabOrder hivOrder = order(adult, general, test("HIV", SensitivityCategory.HIV));
            when(labOrders.findIdsByEncounterIdAndStatusNotIn(eq(general.getId()), anyCollection()))
                .thenReturn(List.of(hivOrder.getId()));
            when(labOrders.findAllById(List.of(hivOrder.getId()))).thenReturn(List.of(hivOrder));

            assertThat(filter.admits(invoiceOf(general))).isFalse();

            Encounter other = visit(adult, null);
            LabOrder count = order(adult, other, test("NFS", null));
            when(labOrders.findIdsByEncounterIdAndStatusNotIn(eq(other.getId()), anyCollection()))
                .thenReturn(List.of(count.getId()));
            when(labOrders.findAllById(List.of(count.getId()))).thenReturn(List.of(count));
            assertThat(filter.admits(invoiceOf(other))).isTrue();
        }

        @Test
        @DisplayName("an invoice line priced from a tagged test's price is hidden too, with no visit at all")
        void anInvoiceLineLinkedToATaggedTest() {
            readingAs(adult, ProxyRelationship.SPOUSE, true, "ALL");
            LabTestDefinition vdrl = test("VDRL", SensitivityCategory.STI);
            when(definitions.findById(vdrl.getId())).thenReturn(Optional.of(vdrl));
            PriceListItem price = PriceListItem.builder()
                .linkType(PriceListLinkType.LAB_TEST_DEFINITION).linkKey(vdrl.getId().toString()).build();
            UUID priceId = UUID.randomUUID();
            when(priceItems.findAllById(List.of(priceId))).thenReturn(List.of(price));

            BillingInvoice invoice = invoiceOf(null);
            InvoiceItem line = new InvoiceItem();
            line.setPriceListItemId(priceId);
            invoice.getInvoiceItems().add(line);

            assertThat(filter.admits(invoice)).isFalse();
        }

        private BillingInvoice invoiceOf(Encounter visit) {
            BillingInvoice invoice = new BillingInvoice();
            invoice.setPatient(adult);
            invoice.setHospital(hospital);
            invoice.setEncounter(visit);
            return invoice;
        }
    }

    @Nested
    @DisplayName("combined panels")
    class Panels {

        @Test
        @DisplayName("an antenatal panel with an HIV component is hidden whole, though the panel itself has no tag")
        void aPanelWithASensitiveComponent() {
            readingAs(adult, ProxyRelationship.SPOUSE, true, "ALL");
            LabTestDefinition hiv = test("HIV", SensitivityCategory.HIV);
            when(definitions.findLiveTaggedAtHospital(hospital.getId())).thenReturn(List.of(hiv));
            LabTestDefinition panel = test("BPN", null);
            panel.setComponents(List.of(
                LabTestComponent.builder().code("hiv").name("Sérologie VIH").build(),
                LabTestComponent.builder().code("GS").name("Groupe sanguin").build()));

            assertThat(filter.admits(order(adult, visit(adult, null), panel))).isFalse();
            assertThat(filter.hidingCategoryOf(order(adult, visit(adult, null), panel)))
                .isEqualTo(SensitivityCategory.HIV);
        }

        @Test
        @DisplayName("a panel with no tagged component stays visible")
        void aPanelWithNoSensitiveComponent() {
            readingAs(adult, ProxyRelationship.SPOUSE, true, "ALL");
            LabTestDefinition hiv = test("HIV", SensitivityCategory.HIV);
            when(definitions.findLiveTaggedAtHospital(hospital.getId())).thenReturn(List.of(hiv));
            LabTestDefinition count = test("NFS", null);
            count.setComponents(List.of(LabTestComponent.builder().code("HB").name("Hémoglobine").build()));

            assertThat(filter.admits(order(adult, visit(adult, null), count))).isTrue();
        }
    }

    @Nested
    @DisplayName("a minor's HIV result")
    class MinorsHivResult {

        @Test
        @DisplayName("hidden from the verified parent by default; read once a clinician released that order")
        void hiddenUntilReleased() {
            LabOrder hivOrder = order(teenager, visit(teenager, null), test("HIV", SensitivityCategory.HIV));
            LabOrder laterOrder = order(teenager, visit(teenager, null), test("HIV", SensitivityCategory.HIV));
            readingAs(teenager, ProxyRelationship.PARENT, true, "ALL");

            when(releases.findReleasedLabOrderIds(grantId)).thenReturn(Set.of());
            assertThat(filter.labResults().test(resultOf(hivOrder))).isFalse();

            when(releases.findReleasedLabOrderIds(grantId)).thenReturn(Set.of(hivOrder.getId()));
            assertThat(filter.labResults().test(resultOf(hivOrder))).isTrue();
            assertThat(filter.labResults().test(resultOf(laterOrder)))
                .as("the next HIV result is hidden until it is released in turn").isFalse();
        }

        @Test
        @DisplayName("a release opens the result even when its visit is HIV-tagged")
        void aReleaseOverridesTheVisitTag() {
            LabOrder hivOrder = order(teenager, visit(teenager, SensitivityCategory.HIV), test("CD4", null));
            readingAs(teenager, ProxyRelationship.LEGAL_GUARDIAN, true, "ALL");
            when(releases.findReleasedLabOrderIds(grantId)).thenReturn(Set.of(hivOrder.getId()));

            assertThat(filter.admits(hivOrder)).isTrue();
        }

        @Test
        @DisplayName("a release is not honoured for an adult, an unverified parent or another relative")
        void whoAReleaseCountsFor() {
            LabOrder adultOrder = order(adult, visit(adult, null), test("HIV", SensitivityCategory.HIV));
            readingAs(adult, ProxyRelationship.PARENT, true, "ALL");
            assertThat(filter.admits(adultOrder)).as("the patient is an adult").isFalse();
            verify(releases, never()).findReleasedLabOrderIds(any());

            LabOrder minorOrder = order(teenager, visit(teenager, null), test("HIV", SensitivityCategory.HIV));
            when(releases.findReleasedLabOrderIds(grantId)).thenReturn(Set.of(minorOrder.getId()));
            readingAs(teenager, ProxyRelationship.PARENT, false, "ALL");
            assertThat(filter.admits(minorOrder)).as("a parent nobody verified").isFalse();
            readingAs(teenager, ProxyRelationship.SIBLING, true, "ALL");
            assertThat(filter.admits(minorOrder)).as("a sibling").isFalse();
        }

        @Test
        @DisplayName("with no grant in hand, nothing tagged is read and no release is looked up")
        void withNoGrantInHand() {
            LabOrder minorOrder = order(teenager, visit(teenager, null), test("HIV", SensitivityCategory.HIV));
            assertThat(filter.admits(minorOrder)).isFalse();
            verify(releases, never()).findReleasedLabOrderIds(any());
        }
    }
}
