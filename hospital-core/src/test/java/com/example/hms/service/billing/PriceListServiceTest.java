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
    private final RoleValidator roles = mock(RoleValidator.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private PriceListService service;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        service = new PriceListService(items, prices, new PriceListLinks(labTests, treatments), labTests, treatments,
            mock(UserRepository.class), roles, scope, audit, CLOCK, "Africa/Ouagadougou");
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
    @DisplayName("the verified super-admin reads every hospital in global view")
    void theSuperAdminReadsInGlobalView() {
        when(roles.requireActiveHospitalId()).thenReturn(null);
        when(items.findAllByOrderByCodeAsc()).thenReturn(List.of());
        assertThat(service.list(null, false)).isEmpty();
        verify(items).findAllByOrderByCodeAsc();
        verify(roles, never()).canReadPriceList(any(), any());
    }
}
