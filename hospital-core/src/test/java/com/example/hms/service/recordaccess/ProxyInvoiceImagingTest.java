package com.example.hms.service.recordaccess;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.enums.ProxyRelationship;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.Patient;
import com.example.hms.model.PriceListItem;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.imaging.ImagingCatalogueService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.when;

/**
 * QA D3450 (r70), the invoice side of the class: an invoice names what it bills. A line « Échographie du 1er
 * trimestre » on the invoice of an ordinary consultation tells the proxy what the hidden study is, so the invoice
 * follows the imaging rule, as it already follows the sensitive lab tests it bills (#1242).
 */
@DisplayName("D3450: an invoice that bills a pregnancy-revealing study is withheld from the proxy, whole")
class ProxyInvoiceImagingTest {

    private final ImagingOrderRepository imagingOrders = mock(ImagingOrderRepository.class);
    private final PriceListItemRepository priceListItems = mock(PriceListItemRepository.class);
    private final ProxyReadScope scope = mock(ProxyReadScope.class);
    private ProxyRecordFilter filter;

    private Hospital hospital;
    private Patient girl;
    private Encounter visit;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        girl = new Patient();
        girl.setId(UUID.randomUUID());
        girl.setGender("F");
        girl.setDateOfBirth(LocalDate.now().minusYears(15));
        visit = new Encounter();
        visit.setId(UUID.randomUUID());
        visit.setPatient(girl);
        visit.setHospital(hospital);
        when(scope.current()).thenReturn(Optional.of(new ProxyReadContext(UUID.randomUUID(), girl.getId(),
            ProxyRelationship.PARENT, true, Set.of("ALL"))));
        filter = new ProxyRecordFilter(new SensitivityClassifierImpl(), mock(LabTestDefinitionRepository.class),
            scope, null, null, null, priceListItems);
        filter.setImagingOrderRepository(imagingOrders);
        filter.setImagingCatalogueService(new ImagingCatalogueService(priceListItems, mock(UserRepository.class),
            mock(RoleValidator.class), mock(ActingScopeResolver.class), mock(AuditEventLogService.class)));
    }

    private BillingInvoice invoiceOf(Encounter encounter, UUID... pricedFrom) {
        BillingInvoice invoice = new BillingInvoice();
        invoice.setPatient(girl);
        invoice.setHospital(hospital);
        invoice.setEncounter(encounter);
        for (UUID id : pricedFrom) {
            InvoiceItem line = new InvoiceItem();
            line.setId(UUID.randomUUID());
            line.setPriceListItemId(id);
            invoice.getInvoiceItems().add(line);
        }
        return invoice;
    }

    private ImagingOrder order(ImagingModality modality, String studyType) {
        ImagingOrder order = new ImagingOrder();
        order.setId(UUID.randomUUID());
        order.setHospital(hospital);
        order.setPatient(girl);
        order.setModality(modality);
        order.setStudyType(studyType);
        return order;
    }

    private PriceListItem exam(String label, String linkKey, Boolean mayRevealPregnancy) {
        PriceListItem item = PriceListItem.builder().hospitalId(hospital.getId()).code("IMG").label(label)
            .category(PriceListCategory.IMAGING).linkType(PriceListLinkType.IMAGING_STUDY).linkKey(linkKey)
            .mayRevealPregnancy(mayRevealPregnancy).build();
        item.setId(UUID.randomUUID());
        when(priceListItems.findUnscopedByHospitalIdAndIdIn(eq(hospital.getId()), anyCollection()))
            .thenReturn(List.of(item));
        return item;
    }

    @Test
    @DisplayName("the r70 visit: its invoice is withheld because the visit holds « Échographie du 1er trimestre »")
    void theInvoiceOfTheVisitIsWithheld() {
        when(imagingOrders.findByEncounterIdAndStatusNotIn(eq(visit.getId()), any()))
            .thenReturn(List.of(order(ImagingModality.ULTRASOUND, "Échographie du 1er trimestre")));

        assertThat(filter.admits(invoiceOf(visit))).isFalse();
    }

    @Test
    @DisplayName("a visit with a chest X-ray only: the invoice reaches the proxy")
    void anOrdinaryInvoiceIsShown() {
        when(imagingOrders.findByEncounterIdAndStatusNotIn(eq(visit.getId()), any()))
            .thenReturn(List.of(order(ImagingModality.XRAY, "Radiographie du thorax")));

        assertThat(filter.admits(invoiceOf(visit))).isTrue();
    }

    @Test
    @DisplayName("a line priced from a flagged or an obstetric catalogue exam withholds the invoice, with or without a visit; a cleared exam does not")
    void aLinePricedFromACatalogueExamIsJudged() {
        PriceListItem obstetric = exam("Échographie obstétricale", "ULTRASOUND:echographie obstetricale", null);
        assertThat(filter.admits(invoiceOf(null, obstetric.getId()))).as("named, nobody decided").isFalse();

        PriceListItem abdominal = exam("Échographie abdominale", "ULTRASOUND:echographie abdominale", null);
        assertThat(filter.admits(invoiceOf(null, abdominal.getId()))).as("uncleared, a girl of 15").isFalse();

        abdominal.setMayRevealPregnancy(false);
        assertThat(filter.admits(invoiceOf(null, abdominal.getId()))).as("cleared by the hospital").isTrue();

        PriceListItem chest = exam("Radiographie du thorax", "XRAY:radiographie du thorax", true);
        assertThat(filter.admits(invoiceOf(null, chest.getId()))).as("flagged").isFalse();
    }

    @Test
    @DisplayName("the patient's sex and age decide the uncleared band on an invoice too")
    void theBandFollowsThePatient() {
        PriceListItem abdominal = exam("Échographie abdominale", "ULTRASOUND:echographie abdominale", null);
        girl.setGender("M");
        assertThat(filter.admits(invoiceOf(null, abdominal.getId()))).as("a recorded male").isTrue();
        girl.setGender("F");
        girl.setDateOfBirth(LocalDate.now().minusYears(70));
        assertThat(filter.admits(invoiceOf(null, abdominal.getId()))).as("a woman of 70").isTrue();
    }
}
