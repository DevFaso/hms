package com.example.hms.service.imaging;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.PriceListItem;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingCatalogueExamDTO;
import com.example.hms.payload.dto.imaging.ImagingCatalogueExamUpdateRequestDTO;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D3450 / D3254 (r70, V361): the hospital's imaging catalogue: what an order takes from the exam it is picked
 * from, who sets an exam's pregnancy flag, and that nothing is read or written across hospitals.
 */
@DisplayName("D3450 / D3254: the imaging catalogue")
class ImagingCatalogueServiceTest {

    private final PriceListItemRepository items = mock(PriceListItemRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final ImagingCatalogueService service = new ImagingCatalogueService(items, users, roles, scope, audit);

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private PriceListItem obstetric;
    private PriceListItem chest;

    @BeforeEach
    void setUp() {
        when(scope.requirePinned()).thenReturn(hospitalId);
        when(roles.getCurrentUserId()).thenReturn(userId);
        obstetric = exam("ECHO-OBS", "Échographie obstétricale", "ULTRASOUND:echographie obstetricale");
        chest = exam("RX-THORAX", "Radiographie du thorax (face)", "XRAY:radiographie thorax face");
        when(items.findActiveImagingCatalogue(hospitalId)).thenReturn(List.of(obstetric, chest));
        when(items.save(any(PriceListItem.class))).thenAnswer(call -> call.getArgument(0));
    }

    private PriceListItem exam(String code, String label, String linkKey) {
        PriceListItem item = PriceListItem.builder().hospitalId(hospitalId).code(code).label(label)
            .category(PriceListCategory.IMAGING)
            .linkType(linkKey == null ? null : PriceListLinkType.IMAGING_STUDY).linkKey(linkKey).build();
        item.setId(UUID.randomUUID());
        when(items.findByIdAndHospitalId(item.getId(), hospitalId)).thenReturn(Optional.of(item));
        when(items.lockByIdAtHospital(item.getId(), hospitalId)).thenReturn(Optional.of(item));
        return item;
    }

    private ImagingOrder order(String studyType, ImagingModality modality) {
        ImagingOrder order = new ImagingOrder();
        order.setId(UUID.randomUUID());
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        order.setHospital(hospital);
        order.setStudyType(studyType);
        order.setModality(modality);
        return order;
    }

    // ── what an order takes from its exam ────────────────────────────────

    @Test
    @DisplayName("an order picked from the catalogue takes the exam's item, code, name, modality and body region")
    void anOrderTakesItsExam() {
        obstetric.setImagingBodyRegion("Pelvis");
        ImagingOrder order = order("ce que le formulaire affichait", ImagingModality.XRAY);

        service.applyTo(order, obstetric.getId(), hospitalId);

        assertThat(order.getCatalogueItemId()).isEqualTo(obstetric.getId());
        assertThat(order.getCatalogueCode()).isEqualTo("ECHO-OBS");
        assertThat(order.getStudyType()).isEqualTo("Échographie obstétricale");
        assertThat(order.getModality()).isEqualTo(ImagingModality.ULTRASOUND);
        assertThat(order.getBodyRegion()).isEqualTo("Pelvis");
    }

    @Test
    @DisplayName("a body region the orderer typed is kept; a name longer than the order's column is cut")
    void typedRegionIsKeptAndLongNamesAreCut() {
        chest.setImagingBodyRegion("Thorax");
        ImagingOrder order = order("x", ImagingModality.XRAY);
        order.setBodyRegion("Hémithorax gauche");
        service.applyTo(order, chest.getId(), hospitalId);
        assertThat(order.getBodyRegion()).isEqualTo("Hémithorax gauche");

        PriceListItem longName = exam("LONG", "E".repeat(200), null);
        ImagingOrder second = order("x", ImagingModality.CT);
        service.applyTo(second, longName.getId(), hospitalId);
        assertThat(second.getStudyType()).hasSize(150);
        assertThat(second.getModality()).as("an unlinked exam names no modality: the orderer's stays")
            .isEqualTo(ImagingModality.CT);
    }

    @Test
    @DisplayName("tenancy: an item of another hospital, an inactive one or one that is not an imaging exam is refused on catalogueItemId")
    void aForeignInactiveOrNonImagingItemIsRefused() {
        UUID foreign = UUID.randomUUID();
        when(items.findByIdAndHospitalId(foreign, hospitalId)).thenReturn(Optional.empty());
        PriceListItem inactive = exam("OLD", "Ancien examen", null);
        inactive.setActive(false);
        PriceListItem labLine = exam("NFS", "Numération", null);
        labLine.setCategory(PriceListCategory.LAB_TEST);

        for (UUID refused : List.of(foreign, inactive.getId(), labLine.getId())) {
            ImagingOrder order = order("x", ImagingModality.XRAY);
            assertThatThrownBy(() -> service.applyTo(order, refused, hospitalId))
                .isInstanceOf(FieldBusinessException.class)
                .satisfies(ex -> assertThat(((FieldBusinessException) ex).getField()).isEqualTo("catalogueItemId"));
            assertThat(order.getCatalogueItemId()).isNull();
        }
        // The item is looked up at the ORDER's hospital only: no unscoped read.
        verify(items, never()).findById(any());
    }

    @Test
    @DisplayName("D3254: a free-text order that is word for word one exam's name (or code) takes that exam; anything else stays free text")
    void aFreeTextOrderTakesTheExamItNames() {
        ImagingOrder byLabel = order("  radiographie du THORAX (face) ", ImagingModality.XRAY);
        service.applyTo(byLabel, null, hospitalId);
        assertThat(byLabel.getCatalogueItemId()).isEqualTo(chest.getId());
        assertThat(byLabel.getCatalogueCode()).isEqualTo("RX-THORAX");

        ImagingOrder byCode = order("rx-thorax", ImagingModality.XRAY);
        service.applyTo(byCode, null, hospitalId);
        assertThat(byCode.getCatalogueItemId()).isEqualTo(chest.getId());

        ImagingOrder otherModality = order("Radiographie du thorax (face)", ImagingModality.CT);
        service.applyTo(otherModality, null, hospitalId);
        assertThat(otherModality.getCatalogueItemId()).as("the exam is an X-ray, the order a CT").isNull();

        ImagingOrder unknown = order("Échographie du 1er trimestre", ImagingModality.ULTRASOUND);
        unknown.setCatalogueItemId(UUID.randomUUID());
        unknown.setCatalogueCode("STALE");
        service.applyTo(unknown, null, hospitalId);
        assertThat(unknown.getCatalogueItemId()).isNull();
        assertThat(unknown.getCatalogueCode()).isNull();
    }

    @Test
    @DisplayName("two exams of the same name: a free-text order is never guessed between them")
    void anAmbiguousNameIsNotGuessed() {
        PriceListItem twin = exam("RX-THORAX-2", "Radiographie du thorax (face)", null);
        when(items.findActiveImagingCatalogue(hospitalId)).thenReturn(List.of(obstetric, chest, twin));
        ImagingOrder order = order("Radiographie du thorax (face)", ImagingModality.XRAY);

        service.applyTo(order, null, hospitalId);

        assertThat(order.getCatalogueItemId()).isNull();
    }

    // ── the catalogue screen ─────────────────────────────────────────────

    @Test
    @DisplayName("the list names each exam's modality and says what an undecided exam is taken for")
    void theListSaysWhatAnUndecidedExamIsTakenFor() {
        when(roles.readsImagingClinically(userId, hospitalId)).thenReturn(true);

        List<ImagingCatalogueExamDTO> list = service.list();

        assertThat(list).extracting(ImagingCatalogueExamDTO::code, ImagingCatalogueExamDTO::modality,
                ImagingCatalogueExamDTO::mayRevealPregnancy, ImagingCatalogueExamDTO::namedAsPregnancyStudy,
                ImagingCatalogueExamDTO::canEdit)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("ECHO-OBS", ImagingModality.ULTRASOUND, null, true, false),
                org.assertj.core.groups.Tuple.tuple("RX-THORAX", ImagingModality.XRAY, null, false, false));
    }

    @Test
    @DisplayName("the list is refused to whoever reads no imaging at the acting hospital (a role held elsewhere)")
    void theListIsRefusedWithoutAnImagingRoleHere() {
        assertThatThrownBy(service::list).isInstanceOf(ClientSafeAccessDeniedException.class);
        verify(items, never()).findActiveImagingCatalogue(any());
    }

    // ── who sets the flag ────────────────────────────────────────────────

    @Test
    @DisplayName("a radiologist of the hospital clears an exam: the flag, who decided and when are stored, and the change is audited by ids")
    void aRadiologistSetsTheFlag() {
        when(roles.hasRole(userId, hospitalId, "RADIOLOGIST")).thenReturn(true);

        ImagingCatalogueExamDTO dto = service.update(chest.getId(),
            ImagingCatalogueExamUpdateRequestDTO.builder().mayRevealPregnancy(false).bodyRegion(" Thorax ").build());

        assertThat(chest.getMayRevealPregnancy()).isFalse();
        assertThat(chest.getMayRevealPregnancySetBy()).isEqualTo(userId);
        assertThat(chest.getMayRevealPregnancySetAt()).isNotNull();
        assertThat(chest.getImagingBodyRegion()).isEqualTo("Thorax");
        assertThat(dto.mayRevealPregnancy()).isFalse();
        assertThat(dto.canEdit()).isTrue();

        // The audit is written after commit; with no transaction here it runs at once.
        ArgumentCaptor<AuditEventRequestDTO> event = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(AuditEventType.CONFIGURATION_CHANGED);
        assertThat(event.getValue().getHospitalId()).isEqualTo(hospitalId);
        assertThat(event.getValue().getResourceId()).isEqualTo(chest.getId().toString());
        assertThat(event.getValue().getEventDescription()).doesNotContain("Radiographie");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) event.getValue().getDetails();
        assertThat(details).containsEntry("mayRevealPregnancy", "false").containsEntry("mayRevealPregnancyBefore", "null");
    }

    @Test
    @DisplayName("a hospital admin flags an exam; put back to « not decided », nobody is named as having decided")
    void anAdminFlagsAndUndecides() {
        when(roles.isHospitalAdmin(userId, hospitalId)).thenReturn(true);

        service.update(chest.getId(), ImagingCatalogueExamUpdateRequestDTO.builder().mayRevealPregnancy(true).build());
        assertThat(chest.getMayRevealPregnancy()).isTrue();

        service.update(chest.getId(), ImagingCatalogueExamUpdateRequestDTO.builder().mayRevealPregnancy(null).build());
        assertThat(chest.getMayRevealPregnancy()).isNull();
        assertThat(chest.getMayRevealPregnancySetBy()).isNull();
        assertThat(chest.getMayRevealPregnancySetAt()).isNull();
    }

    @Test
    @DisplayName("a doctor, a nurse, an accountant, or a radiologist of ANOTHER hospital cannot change an exam")
    void othersCannotSetTheFlag() {
        // No role stubbed at this hospital: whatever the token says, nothing is held HERE.
        ImagingCatalogueExamUpdateRequestDTO clear =
            ImagingCatalogueExamUpdateRequestDTO.builder().mayRevealPregnancy(false).build();

        assertThatThrownBy(() -> service.update(obstetric.getId(), clear))
            .isInstanceOf(ClientSafeAccessDeniedException.class);

        assertThat(obstetric.getMayRevealPregnancy()).isNull();
        verify(items, never()).lockByIdAtHospital(any(), any());
        verify(items, never()).save(any());
    }

    @Test
    @DisplayName("tenancy: an exam of another hospital answers as a missing one, and nothing is written")
    void anotherHospitalsExamIsMissing() {
        when(roles.isHospitalAdmin(userId, hospitalId)).thenReturn(true);
        UUID foreign = UUID.randomUUID();
        when(items.lockByIdAtHospital(foreign, hospitalId)).thenReturn(Optional.empty());
        ImagingCatalogueExamUpdateRequestDTO clear =
            ImagingCatalogueExamUpdateRequestDTO.builder().mayRevealPregnancy(false).build();

        assertThatThrownBy(() -> service.update(foreign, clear)).isInstanceOf(ResourceNotFoundException.class);
        verify(items, never()).save(any());
    }

    // ── what a proxy's read asks ─────────────────────────────────────────

    @Test
    @DisplayName("the flags of a proxy's read are taken at each order's own hospital, outside the caller's scope, and only decided ones")
    void flagsAreReadAtTheOrdersHospital() {
        chest.setMayRevealPregnancy(false);
        ImagingOrder cleared = order("Radiographie du thorax (face)", ImagingModality.XRAY);
        cleared.setCatalogueItemId(chest.getId());
        ImagingOrder undecided = order("Échographie obstétricale", ImagingModality.ULTRASOUND);
        undecided.setCatalogueItemId(obstetric.getId());
        ImagingOrder freeText = order("Scanner", ImagingModality.CT);
        when(items.findUnscopedByHospitalIdAndIdIn(any(), anyCollection())).thenReturn(List.of(chest, obstetric));

        Map<UUID, Boolean> flags = service.pregnancyFlagsOf(List.of(cleared, undecided, freeText));

        assertThat(flags).containsOnly(Map.entry(cleared.getId(), false));
        verify(items).findUnscopedByHospitalIdAndIdIn(org.mockito.ArgumentMatchers.eq(hospitalId), anyCollection());
        verify(items, never()).findAllById(any());
    }
}
