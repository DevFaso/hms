package com.example.hms.service.billing;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.PriceListItem;
import com.example.hms.model.PriceListPrice;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.billing.PriceListDeactivateRequestDTO;
import com.example.hms.payload.dto.billing.PriceListItemRequestDTO;
import com.example.hms.payload.dto.billing.PriceListPriceRequestDTO;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.PriceListPriceRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 2 (owner decision D-1): who writes and reads the price list, and what each write leaves in
 * the audit. The role is checked on the assignment AT the acting hospital before any lookup, so the platform
 * super-admin (who holds the hospital admin AUTHORITY through RoleExpansion), the cashier and a clinician never write.
 */
@DisplayName("Price list: access and audit")
class PriceListServiceTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    private final PriceListItemRepository items = mock(PriceListItemRepository.class);
    private final PriceListPriceRepository prices = mock(PriceListPriceRepository.class);
    private final LabTestDefinitionRepository labTests = mock(LabTestDefinitionRepository.class);
    private final TreatmentRepository treatments = mock(TreatmentRepository.class);
    private final com.example.hms.repository.HospitalRepository hospitals =
        mock(com.example.hms.repository.HospitalRepository.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final com.example.hms.repository.InvoiceItemRepository invoiceLines =
        mock(com.example.hms.repository.InvoiceItemRepository.class);
    private PriceListService service;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        service = new PriceListService(items, prices, new PriceListLinks(labTests, treatments), invoiceLines, labTests, treatments,
            mock(UserRepository.class), hospitals, roles, scope, audit, CLOCK, "Africa/Ouagadougou");
        when(scope.requirePinned()).thenReturn(HOSPITAL);
        when(roles.requireActiveHospitalId()).thenReturn(HOSPITAL);
        when(roles.getCurrentUserId()).thenReturn(ACTOR);
        when(items.saveAndFlush(any())).thenAnswer(call -> withId(call.getArgument(0)));
        when(prices.saveAndFlush(any())).thenAnswer(call -> withId(call.getArgument(0)));
    }

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static <E extends com.example.hms.model.BaseEntity> E withId(E entity) {
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID());
        }
        return entity;
    }

    private static PriceListItemRequestDTO consultation() {
        return PriceListItemRequestDTO.builder()
            .code("cs-gen").label("Consultation générale").category(PriceListCategory.CONSULTATION)
            .linkType(PriceListLinkType.SYSTEM).linkKey("CONSULTATION_DEFAULT")
            .unitPrice(new BigDecimal("2000")).build();
    }

    @Test
    @DisplayName("without HOSPITAL_ADMIN or ACCOUNTANT at the hospital (super-admin alone, cashier, doctor) nothing is written or read")
    void aCallerWithoutAMaintainerAssignmentIsRefusedBeforeAnyLookup() {
        when(roles.canMaintainPriceList(ACTOR, HOSPITAL)).thenReturn(false);
        UUID itemId = UUID.randomUUID();

        assertThatThrownBy(() -> service.create(consultation())).isInstanceOf(ClientSafeAccessDeniedException.class)
            .hasMessage("Seuls l’administrateur de l’établissement et le comptable modifient la grille tarifaire.");
        assertThatThrownBy(() -> service.addPrice(itemId, PriceListPriceRequestDTO.builder()
            .unitPrice(BigDecimal.TEN).changeReason("x").build())).isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> service.update(itemId, null)).isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> service.deactivate(itemId, null)).isInstanceOf(ClientSafeAccessDeniedException.class);
        verifyNoInteractions(items, prices, audit);
    }

    @Test
    @DisplayName("the super-admin's global view (no hospital named) never writes")
    void globalViewNeverWrites() {
        when(scope.requirePinned()).thenThrow(new HospitalScopeRefusedException("Select a hospital"));
        assertThatThrownBy(() -> service.create(consultation())).isInstanceOf(HospitalScopeRefusedException.class);
        verifyNoInteractions(items, prices, audit);
        verify(roles, never()).canMaintainPriceList(any(), any());
    }

    @Test
    @DisplayName("an accountant creates CS-GEN at 2 000 F from today; the audit row carries ids only")
    void anAccountantCreatesAnItemAndTheAuditCarriesIdsOnly() {
        when(roles.canMaintainPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        when(prices.findHistory(any(), any())).thenReturn(List.of());

        var created = service.create(PriceListItemRequestDTO.builder()
            .code(" cs-gen ").label("Consultation générale").category(PriceListCategory.CONSULTATION)
            .linkType(PriceListLinkType.SYSTEM).linkKey("CONSULTATION_DEFAULT")
            .unitPrice(new BigDecimal("2000")).changeReason("Grille 2026").build());

        ArgumentCaptor<PriceListItem> item = ArgumentCaptor.forClass(PriceListItem.class);
        verify(items).saveAndFlush(item.capture());
        assertThat(item.getValue().getCode()).isEqualTo("CS-GEN");
        assertThat(item.getValue().getHospitalId()).isEqualTo(HOSPITAL);
        ArgumentCaptor<PriceListPrice> price = ArgumentCaptor.forClass(PriceListPrice.class);
        verify(prices).saveAndFlush(price.capture());
        assertThat(price.getValue().getEffectiveFrom()).isEqualTo(TODAY);
        assertThat(price.getValue().getUnitPrice()).isEqualByComparingTo("2000");
        assertThat(created.getCode()).isEqualTo("CS-GEN");

        ArgumentCaptor<AuditEventRequestDTO> row = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(row.capture());
        AuditEventRequestDTO written = row.getValue();
        assertThat(written.getEventType()).isEqualTo(AuditEventType.PRICE_LIST_ITEM_CREATED);
        assertThat(written.getHospitalId()).isEqualTo(HOSPITAL);
        assertThat(written.getUserId()).isEqualTo(ACTOR);
        assertThat(written.getResourceId()).isEqualTo(item.getValue().getId().toString());
        assertThat(written.getEventDescription())
            .doesNotContain("Consultation", "2000", "2 000", "Grille 2026", "CS-GEN");
        assertThat(written.getPatientId()).isNull();
    }

    @Test
    @DisplayName("an empty body is refused once, naming code, label, category and unit price")
    void anEmptyBodyNamesEveryField() {
        when(roles.canMaintainPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        assertThatThrownBy(() -> service.create(new PriceListItemRequestDTO()))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getFieldErrors())
                .containsOnlyKeys("code", "label", "category", "unitPrice"));
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("a duplicate code is refused naming the code")
    void aDuplicateCodeIsRefusedNamingIt() {
        when(roles.canMaintainPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        when(items.existsByHospitalIdAndCode(HOSPITAL, "CS-GEN")).thenReturn(true);
        assertThatThrownBy(() -> service.create(consultation()))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("code");
                assertThat(e.getMessage()).contains("CS-GEN");
            });
        verify(items, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a code shaped like a spreadsheet formula is refused")
    void aFormulaLikeCodeIsRefused() {
        when(roles.canMaintainPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        PriceListItemRequestDTO body = consultation();
        body.setCode("=cmd|' /C calc'!A0");
        assertThatThrownBy(() -> service.create(body))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("code"));
    }

    @Test
    @DisplayName("another hospital's item answers 404, as a missing one")
    void anotherHospitalsItemIsNotFound() {
        when(roles.canMaintainPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        UUID foreign = UUID.randomUUID();
        when(items.lockByIdAtHospital(foreign, HOSPITAL)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addPrice(foreign, PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("2500")).changeReason("Hausse").build()))
            .isInstanceOf(ResourceNotFoundException.class);
        when(roles.canReadPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        when(items.findByIdAndHospitalId(foreign, HOSPITAL)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(foreign)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a new price needs its reason")
    void aNewPriceNeedsAReason() {
        when(roles.canMaintainPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        PriceListItem item = withId(PriceListItem.builder().hospitalId(HOSPITAL).code("CS-GEN").active(true).build());
        when(items.lockByIdAtHospital(item.getId(), HOSPITAL)).thenReturn(Optional.of(item));
        assertThatThrownBy(() -> service.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("2500")).changeReason("  ").build()))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getFieldErrors()).containsOnlyKeys("changeReason"));
    }

    @Test
    @DisplayName("deactivating needs a reason, and keeps who, when and why")
    void deactivatingNeedsAReason() {
        when(roles.canMaintainPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        PriceListItem item = withId(PriceListItem.builder().hospitalId(HOSPITAL).code("CS-GEN").active(true).build());
        when(items.lockByIdAtHospital(item.getId(), HOSPITAL)).thenReturn(Optional.of(item));
        assertThatThrownBy(() -> service.deactivate(item.getId(), new PriceListDeactivateRequestDTO(" ")))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("reason"));
        assertThat(item.isActive()).isTrue();

        service.deactivate(item.getId(), new PriceListDeactivateRequestDTO("Acte retiré de la grille"));
        assertThat(item.isActive()).isFalse();
        assertThat(item.getDeactivatedBy()).isEqualTo(ACTOR);
        assertThat(item.getDeactivationReason()).isEqualTo("Acte retiré de la grille");
        ArgumentCaptor<AuditEventRequestDTO> row = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(row.capture());
        assertThat(row.getValue().getEventType()).isEqualTo(AuditEventType.PRICE_LIST_ITEM_DEACTIVATED);
        assertThat(row.getValue().getEventDescription()).doesNotContain("Acte retiré");
    }

    @Test
    @DisplayName("a doctor does not read the price list; a receptionist does")
    void readersHoldAReadingRoleAtTheHospital() {
        when(scope.isVerifiedSuperAdmin()).thenReturn(false);
        when(roles.canReadPriceList(ACTOR, HOSPITAL)).thenReturn(false);
        assertThatThrownBy(() -> service.list(null, false)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(items);

        when(roles.canReadPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        when(items.findByHospitalIdOrderByCodeAsc(HOSPITAL)).thenReturn(List.of());
        assertThat(service.list(null, false)).isEmpty();
    }

    @Test
    @DisplayName("PR 10 review (QA D2700): a revised lab test is offered under the id its link is stored on")
    void theLabTestOptionIsTheStoredFirstVersion() {
        when(roles.canReadPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        com.example.hms.model.LabTestDefinition v1 = new com.example.hms.model.LabTestDefinition();
        v1.setId(UUID.randomUUID());
        v1.setTestCode("TWP-NFS");
        v1.setName("NFS");
        v1.setActive(false);
        com.example.hms.model.LabTestDefinition v2 = new com.example.hms.model.LabTestDefinition();
        v2.setId(UUID.randomUUID());
        v2.setTestCode("TWP-NFS");
        v2.setName("NFS (automate)");
        v2.setActive(true);
        v2.setSupersedesDefinitionId(v1.getId());
        when(labTests.findVisibleAt(HOSPITAL)).thenReturn(List.of(v1, v2));
        when(labTests.findById(v1.getId())).thenReturn(Optional.of(v1));
        when(treatments.findByHospital_Id(HOSPITAL)).thenReturn(List.of());

        // One option, the active version's name, valued with the first version's id: the form's select shows the
        // linked test selected (the item's linkKey is that id) instead of an empty required field.
        assertThat(service.linkOptions().getLabTests()).singleElement().satisfies(option -> {
            assertThat(option.getId()).isEqualTo(v1.getId());
            assertThat(option.getName()).isEqualTo("NFS (automate)");
        });
    }

    @Test
    @DisplayName("no hospital and not the verified super-admin: refused, never read across hospitals")
    void aNullHospitalWithoutTheVerifiedSuperAdminIsRefused() {
        when(roles.requireActiveHospitalId()).thenReturn(null);
        when(scope.isVerifiedSuperAdmin()).thenReturn(false);
        UUID anyItem = UUID.randomUUID();
        assertThatThrownBy(() -> service.list(null, true)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.get(anyItem)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.linkOptions()).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(items, prices, labTests, treatments);
    }

    @Test
    @DisplayName("a concurrent create that loses on the link is refused naming the link, not the code")
    void aConcurrentLinkCollisionNamesTheLink() {
        when(roles.canMaintainPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("x",
            new RuntimeException("duplicate key value violates unique constraint \"uq_price_list_item_active_link\"")))
            .when(items).saveAndFlush(any());
        assertThatThrownBy(() -> service.create(consultation()))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("linkKey"));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("x",
            new RuntimeException("duplicate key value violates unique constraint \"uq_price_list_item_code\"")))
            .when(items).saveAndFlush(any());
        assertThatThrownBy(() -> service.create(consultation()))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("code"));
    }

    @Test
    @DisplayName("QA D2070: a taken link is named as the form shows it — a system key, a blood product, a lab test")
    void aTakenLinkIsNamedReadably() {
        when(roles.canMaintainPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        PriceListItem holder = withId(PriceListItem.builder().hospitalId(HOSPITAL).code("CS-GEN").active(true).build());
        when(items.findActiveByLink(any(), any(), any())).thenReturn(List.of(holder));
        assertThatThrownBy(() -> service.create(consultation()))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getMessage())
                .contains("« Consultation par défaut »").contains("CS-GEN").doesNotContain("CONSULTATION_DEFAULT"));

        PriceListItem item = withId(PriceListItem.builder().hospitalId(HOSPITAL).code("CGR").active(true).build());
        when(items.lockByIdAtHospital(item.getId(), HOSPITAL)).thenReturn(Optional.of(item));
        assertThatThrownBy(() -> service.update(item.getId(), com.example.hms.payload.dto.billing.PriceListItemUpdateRequestDTO
            .builder().label("Culot").category(PriceListCategory.CONSULTATION)
            .linkType(PriceListLinkType.TRANSFUSION_PRODUCT).linkKey("PACKED_RED_CELLS").build()))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getMessage())
                .contains("globules rouges").doesNotContain("PACKED_RED_CELLS"));

        com.example.hms.model.LabTestDefinition nfs = new com.example.hms.model.LabTestDefinition();
        nfs.setId(UUID.randomUUID());
        nfs.setTestCode("NFS");
        nfs.setName("Numération formule sanguine");
        nfs.setActive(true);
        when(labTests.findById(nfs.getId())).thenReturn(Optional.of(nfs));
        assertThatThrownBy(() -> service.update(item.getId(), com.example.hms.payload.dto.billing.PriceListItemUpdateRequestDTO
            .builder().label("NFS").category(PriceListCategory.CONSULTATION)
            .linkType(PriceListLinkType.LAB_TEST_DEFINITION).linkKey(nfs.getId().toString()).build()))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getMessage())
                .contains("« NFS · Numération formule sanguine »").doesNotContain(nfs.getId().toString()));
    }

    @Test
    @DisplayName("a deactivated item takes no edit")
    void aDeactivatedItemTakesNoEdit() {
        when(roles.canMaintainPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        PriceListItem item = withId(PriceListItem.builder().hospitalId(HOSPITAL).code("CS-GEN").active(false).build());
        when(items.lockByIdAtHospital(item.getId(), HOSPITAL)).thenReturn(Optional.of(item));
        assertThatThrownBy(() -> service.update(item.getId(), com.example.hms.payload.dto.billing.PriceListItemUpdateRequestDTO
            .builder().label("Consultation").category(PriceListCategory.CONSULTATION).build()))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .hasMessageContaining("désactivé");
        verify(items, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("re-saving an item with its own link is not a collision")
    void reSavingTheItemsOwnLinkSucceeds() {
        when(roles.canMaintainPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        PriceListItem item = withId(PriceListItem.builder().hospitalId(HOSPITAL).code("CS-GEN").active(true)
            .linkType(PriceListLinkType.SYSTEM).linkKey("CONSULTATION_DEFAULT").build());
        when(items.lockByIdAtHospital(item.getId(), HOSPITAL)).thenReturn(Optional.of(item));
        when(items.findActiveByLink(HOSPITAL, PriceListLinkType.SYSTEM, "CONSULTATION_DEFAULT")).thenReturn(List.of(item));
        when(prices.findHistory(any(), any())).thenReturn(List.of());
        var saved = service.update(item.getId(), com.example.hms.payload.dto.billing.PriceListItemUpdateRequestDTO.builder()
            .label("Consultation générale").category(PriceListCategory.CONSULTATION)
            .linkType(PriceListLinkType.SYSTEM).linkKey("CONSULTATION_DEFAULT").build());
        assertThat(saved.getLabel()).isEqualTo("Consultation générale");
        assertThat(saved.getLinkKey()).isEqualTo("CONSULTATION_DEFAULT");
    }

    @Test
    @DisplayName("an edit that loses a race on the link is refused naming the link")
    void anEditThatLosesTheLinkRaceNamesTheLink() {
        when(roles.canMaintainPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        PriceListItem item = withId(PriceListItem.builder().hospitalId(HOSPITAL).code("HOSP-JOUR").active(true).build());
        when(items.lockByIdAtHospital(item.getId(), HOSPITAL)).thenReturn(Optional.of(item));
        when(items.findActiveByLink(any(), any(), any())).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("x",
                new RuntimeException("duplicate key value violates unique constraint \"uq_price_list_item_active_link\"")))
            .when(items).saveAndFlush(any());
        assertThatThrownBy(() -> service.update(item.getId(), com.example.hms.payload.dto.billing.PriceListItemUpdateRequestDTO
            .builder().label("Journée").category(PriceListCategory.BED_DAY)
            .linkType(PriceListLinkType.SYSTEM).linkKey("BED_DAY").build()))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("linkKey");
                // QA D2070: named the way the form shows it, never the stored key.
                assertThat(e.getMessage()).contains("« Journée d’hospitalisation »").contains("vient d")
                    .doesNotContain("BED_DAY");
            });
    }

    @Test
    @DisplayName("QA D2071: in global view each row names its hospital; a pinned view reads no hospital")
    void theGlobalViewNamesEachRowsHospital() {
        UUID other = UUID.randomUUID();
        PriceListItem a = withId(PriceListItem.builder().hospitalId(HOSPITAL).code("CS-GEN").active(true)
            .category(PriceListCategory.CONSULTATION).build());
        PriceListItem b = withId(PriceListItem.builder().hospitalId(other).code("CS-GEN").active(true)
            .category(PriceListCategory.CONSULTATION).build());
        com.example.hms.model.Hospital ha = new com.example.hms.model.Hospital();
        ha.setId(HOSPITAL);
        ha.setName("Clinique A");
        com.example.hms.model.Hospital hb = new com.example.hms.model.Hospital();
        hb.setId(other);
        hb.setName("Clinique B");
        when(hospitals.findAllById(any())).thenReturn(List.of(ha, hb));
        when(roles.requireActiveHospitalId()).thenReturn(null);
        when(scope.isVerifiedSuperAdmin()).thenReturn(true);
        when(items.findAllByOrderByCodeAsc()).thenReturn(List.of(a, b));
        assertThat(service.list(null, false)).extracting(com.example.hms.payload.dto.billing.PriceListItemDTO::getHospitalName)
            .containsExactly("Clinique A", "Clinique B");

        when(roles.requireActiveHospitalId()).thenReturn(HOSPITAL);
        when(roles.canReadPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        when(items.findByHospitalIdOrderByCodeAsc(HOSPITAL)).thenReturn(List.of(a));
        org.mockito.Mockito.clearInvocations(hospitals);
        assertThat(service.list(null, false)).extracting(com.example.hms.payload.dto.billing.PriceListItemDTO::getHospitalName).containsOnlyNulls();
        verify(hospitals, never()).findAllById(any());
    }

    @Test
    @DisplayName("the verified super-admin reads every hospital in global view")
    void theSuperAdminReadsInGlobalView() {
        when(roles.requireActiveHospitalId()).thenReturn(null);
        when(scope.isVerifiedSuperAdmin()).thenReturn(true);
        when(items.findAllByOrderByCodeAsc()).thenReturn(List.of());
        assertThat(service.list(null, false)).isEmpty();
        verify(items).findAllByOrderByCodeAsc();
        verify(roles, never()).canReadPriceList(any(), any());
    }

    @Test
    @DisplayName("PR 3 (#1179 review): the global view reads versions, names and links in chunks, never one unbounded IN")
    void theGlobalViewReadsInChunks() {
        when(roles.requireActiveHospitalId()).thenReturn(null);
        when(scope.isVerifiedSuperAdmin()).thenReturn(true);
        List<com.example.hms.model.PriceListItem> many = new java.util.ArrayList<>();
        for (int n = 0; n < 1201; n++) {
            com.example.hms.model.PriceListItem item = com.example.hms.model.PriceListItem.builder()
                .hospitalId(UUID.randomUUID()).code("C" + n).label("L" + n)
                .category(PriceListCategory.OTHER_SERVICE).active(true)
                .linkType(com.example.hms.enums.PriceListLinkType.TREATMENT).linkKey(UUID.randomUUID().toString())
                .build();
            item.setId(UUID.randomUUID());
            many.add(item);
        }
        when(items.findAllByOrderByCodeAsc()).thenReturn(many);
        List<java.util.Collection<UUID>> priceCalls = new java.util.ArrayList<>();
        when(prices.findByItemIds(any())).thenAnswer(call -> {
            priceCalls.add(List.copyOf(call.<java.util.Collection<UUID>>getArgument(0)));
            return List.of();
        });
        List<Iterable<UUID>> treatmentCalls = new java.util.ArrayList<>();
        when(treatments.findAllById(any())).thenAnswer(call -> {
            treatmentCalls.add(call.getArgument(0));
            return List.of();
        });

        assertThat(service.list(null, false)).hasSize(1201);

        assertThat(priceCalls).hasSize(3).allSatisfy(ids -> assertThat(ids).hasSizeLessThanOrEqualTo(500));
        assertThat(priceCalls.stream().mapToInt(java.util.Collection::size).sum()).isEqualTo(1201);
        assertThat(treatmentCalls).hasSize(3);
        treatmentCalls.forEach(ids -> assertThat(ids).hasSizeLessThanOrEqualTo(500));
    }

    @Test
    @DisplayName("PR 3 (#1179 review): a same-day correction is refused once an invoice line copied that version")
    void anInvoicedVersionIsNeverCorrected() {
        com.example.hms.model.PriceListItem item = com.example.hms.model.PriceListItem.builder().hospitalId(HOSPITAL)
            .code("CS-GEN").label("Consultation").category(PriceListCategory.CONSULTATION).active(true).build();
        item.setId(UUID.randomUUID());
        com.example.hms.model.PriceListPrice today = com.example.hms.model.PriceListPrice.builder().hospitalId(HOSPITAL)
            .item(item).unitPrice(new BigDecimal("2000")).effectiveFrom(TODAY).build();
        today.setId(UUID.randomUUID());
        today.setCreatedAt(java.time.LocalDateTime.now(CLOCK.withZone(java.time.ZoneId.systemDefault())));
        when(roles.canMaintainPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        when(items.lockByIdAtHospital(item.getId(), HOSPITAL)).thenReturn(java.util.Optional.of(item));
        when(prices.findHistory(item.getId(), HOSPITAL)).thenReturn(List.of(today));
        when(invoiceLines.existsByPriceListPriceIdIn(List.of(today.getId()))).thenReturn(true);

        PriceListPriceRequestDTO correction = PriceListPriceRequestDTO.builder().unitPrice(new BigDecimal("2200"))
            .changeReason("Erreur de saisie").replaceScheduled(true).build();
        UUID itemId = item.getId();
        assertThatThrownBy(() -> service.addPrice(itemId, correction))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("effectiveFrom");
                assertThat(e.getMessage()).contains("2 000 F CFA").contains("déjà été facturé");
            });
        verify(prices, never()).saveAndFlush(any());

        // Not yet invoiced, the same correction goes through.
        when(invoiceLines.existsByPriceListPriceIdIn(List.of(today.getId()))).thenReturn(false);
        service.addPrice(itemId, correction);
        verify(prices).saveAndFlush(any());
    }

    @Test
    @DisplayName("review #1206: a label that starts like a spreadsheet formula is refused on the screen, FR and EN")
    void aFormulaLookingLabelIsRefused() {
        when(roles.canMaintainPriceList(ACTOR, HOSPITAL)).thenReturn(true);
        for (String label : List.of("=HYPERLINK(1)", "+1", "- Pansement", "@SUM(A1)")) {
            PriceListItemRequestDTO body = PriceListItemRequestDTO.builder().code("ADM-X").label(label)
                .category(PriceListCategory.OTHER_SERVICE).unitPrice(new BigDecimal("100")).build();
            assertThatThrownBy(() -> service.create(body)).as(label)
                .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("label");
                    assertThat(e.getMessage()).startsWith("Le libellé ne peut pas commencer par =, +, -, @");
                });
        }
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        PriceListItemRequestDTO english = PriceListItemRequestDTO.builder().code("ADM-X").label("=1")
            .category(PriceListCategory.OTHER_SERVICE).unitPrice(new BigDecimal("100")).build();
        assertThatThrownBy(() -> service.create(english))
            .hasMessageStartingWith("The label cannot start with =, +, -, @");
        verify(items, never()).saveAndFlush(any());
    }
}
