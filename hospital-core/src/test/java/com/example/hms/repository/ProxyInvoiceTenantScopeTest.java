package com.example.hms.repository;

import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.enums.ProxyRelationship;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.PriceListItem;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.recordaccess.ProxyReadContext;
import com.example.hms.service.recordaccess.ProxyReadScope;
import com.example.hms.service.recordaccess.ProxyRecordFilter;
import com.example.hms.service.recordaccess.SensitivityClassifierImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of #1242 (Medium, privacy): the proxy filter read the price-list
 * items of an invoice through {@code findAllById}, which the tenant filter
 * narrows to the CALLER's hospitals. A family proxy holds no assignment at
 * the hospital that billed the grantor, so the items came back empty and the
 * invoice with « Sérologie VIH » on it was shown (the QA D1212 trap).
 *
 * <p>Runs the real {@code TenantAwareJpaRepository} and
 * {@code TenantScopeSpecification} against H2, with the proxy's own scope on
 * the request: nothing here mocks the scoping away.
 */
@TenantScopedDataJpaTest
@Import(PatientRepositoryRegistrationScopeTest.JpaConfig.class)
@DisplayName("#1242: a proxy's invoice read judges price-list items of a hospital the proxy does not work at")
class ProxyInvoiceTenantScopeTest {

    @Autowired private PriceListItemRepository priceListItemRepository;
    @Autowired private LabTestDefinitionRepository labTestDefinitionRepository;
    @Autowired private HospitalRepository hospitalRepository;

    private final ProxyReadScope scope = new ProxyReadScope();
    private ProxyRecordFilter filter;
    private Hospital billedAt;
    private Hospital proxysOwn;
    private Patient grantor;
    private UUID hivPriceId;
    private UUID countPriceId;

    @BeforeEach
    void seed() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        billedAt = hospitalRepository.save(Hospital.builder().name("Hôpital B").code("PIB").build());
        proxysOwn = hospitalRepository.save(Hospital.builder().name("Hôpital A").code("PIA").build());

        LabTestDefinition hiv = labTestDefinitionRepository.save(definition("SRV", "Sérologie VIH",
            SensitivityCategory.HIV));
        LabTestDefinition count = labTestDefinitionRepository.save(definition("NFS", "Numération formule sanguine",
            null));
        hivPriceId = priceListItemRepository.save(price("LAB-SRV", "Sérologie VIH", hiv)).getId();
        countPriceId = priceListItemRepository.save(price("LAB-NFS", "NFS", count)).getId();
        HospitalContextHolder.clear();

        grantor = new Patient();
        grantor.setId(UUID.randomUUID());
        grantor.setDateOfBirth(LocalDate.now().minusYears(40));
        filter = new ProxyRecordFilter(new SensitivityClassifierImpl(), labTestDefinitionRepository, scope, null, null,
            null, priceListItemRepository);

        // The proxy: a nurse at hospital A, holding a family-access grant on the grantor's record.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .activeHospitalId(proxysOwn.getId())
            .permittedHospitalIds(Set.of(proxysOwn.getId()))
            .build());
        scope.enter(new ProxyReadContext(UUID.randomUUID(), grantor.getId(), ProxyRelationship.SPOUSE, false,
            Set.of("ALL")));
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    private LabTestDefinition definition(String code, String name, SensitivityCategory category) {
        return LabTestDefinition.builder()
            .testCode(code)
            .name(name)
            .hospital(billedAt)
            .approvalStatus(LabTestDefinitionApprovalStatus.ACTIVE)
            .sensitivityCategory(category)
            .build();
    }

    private PriceListItem price(String code, String label, LabTestDefinition test) {
        return PriceListItem.builder()
            .hospitalId(billedAt.getId())
            .code(code)
            .label(label)
            .category(PriceListCategory.LAB_TEST)
            .linkType(PriceListLinkType.LAB_TEST_DEFINITION)
            .linkKey(test.getId().toString())
            .build();
    }

    private BillingInvoice invoiceWith(Hospital hospital, UUID... priceListItemIds) {
        BillingInvoice invoice = new BillingInvoice();
        invoice.setPatient(grantor);
        invoice.setHospital(hospital);
        for (UUID id : priceListItemIds) {
            InvoiceItem line = new InvoiceItem();
            // Lines are a Set keyed by id: without one, two lines are the same line.
            line.setId(UUID.randomUUID());
            line.setPriceListItemId(id);
            invoice.getInvoiceItems().add(line);
        }
        return invoice;
    }

    @Test
    @DisplayName("the trap itself: the scoped finder answers the proxy with nothing for the grantor's hospital")
    void theScopedFinderIsBlindThere() {
        assertThat(priceListItemRepository.findAllById(List.of(hivPriceId, countPriceId))).isEmpty();
        assertThat(priceListItemRepository.findUnscopedByHospitalIdAndIdIn(billedAt.getId(),
            List.of(hivPriceId, countPriceId))).hasSize(2);
    }

    @Test
    @DisplayName("the invoice that bills the HIV serology at hospital B is withheld from a proxy who works at hospital A")
    void theHivInvoiceIsWithheld() {
        assertThat(filter.admits(invoiceWith(billedAt, hivPriceId))).isFalse();
        assertThat(filter.admits(invoiceWith(billedAt, countPriceId, hivPriceId))).isFalse();
    }

    @Test
    @DisplayName("an invoice that bills only ordinary tests there is still shown")
    void anOrdinaryInvoiceIsShown() {
        assertThat(filter.admits(invoiceWith(billedAt, countPriceId))).isTrue();
        assertThat(filter.admits(invoiceWith(billedAt))).isTrue();
    }

    @Test
    @DisplayName("the items are read at the invoice's own hospital only: another hospital's, or an unknown one, withholds")
    void failClosed() {
        // The item is hospital B's; an invoice of hospital A that names it cannot be judged.
        assertThat(filter.admits(invoiceWith(proxysOwn, countPriceId))).isFalse();
        assertThat(filter.admits(invoiceWith(billedAt, UUID.randomUUID()))).isFalse();
        assertThat(filter.admits(invoiceWith(null, countPriceId))).isFalse();
    }
}
