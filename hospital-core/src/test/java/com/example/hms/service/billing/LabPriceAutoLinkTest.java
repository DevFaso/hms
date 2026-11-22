package com.example.hms.service.billing;

import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.PriceListItem;
import com.example.hms.payload.dto.billing.PriceListAutoLinkReportDTO;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA r70 (lab set-up): lab price lines imported before the lab had any live test stayed unlinked (9 of 11) and had to
 * be linked one by one. They are linked by code: the same code, or the same code after its prefix (« LAB-GLY » and
 * « TWP-GLY »); never a guess between two tests, never a test another line already holds.
 */
class LabPriceAutoLinkTest {

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final PriceListItemRepository items = mock(PriceListItemRepository.class);
    private final LabTestDefinitionRepository tests = mock(LabTestDefinitionRepository.class);
    private final PriceListLinks links = mock(PriceListLinks.class);
    private final List<PriceListItem> list = new ArrayList<>();
    private final List<LabTestDefinition> live = new ArrayList<>();
    private LabPriceAutoLink autoLink;

    @BeforeEach
    void setUp() {
        autoLink = new LabPriceAutoLink(items, tests, links, mock(AuditEventLogService.class));
        when(items.findByHospitalIdOrderByCodeAsc(hospitalId)).thenReturn(list);
        when(tests.findByHospital_IdAndActiveTrue(hospitalId)).thenReturn(live);
        when(tests.findByHospitalIsNullAndActiveTrue()).thenReturn(List.of());
        // The screen's rule: a test is stored on its lineage; here, its own id.
        when(links.normalise(eq(hospitalId), eq(PriceListLinkType.LAB_TEST_DEFINITION), anyString()))
            .thenAnswer(call -> call.getArgument(2));
        when(items.findActiveByLink(eq(hospitalId), eq(PriceListLinkType.LAB_TEST_DEFINITION), anyString()))
            .thenReturn(List.of());
    }

    private PriceListItem line(String code, PriceListCategory category) {
        PriceListItem item = PriceListItem.builder().hospitalId(hospitalId).code(code).label(code).category(category)
            .active(true).build();
        item.setId(UUID.randomUUID());
        list.add(item);
        return item;
    }

    private LabTestDefinition test(String code) {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        LabTestDefinition definition = new LabTestDefinition();
        definition.setId(UUID.randomUUID());
        definition.setTestCode(code);
        definition.setName("Test " + code);
        definition.setHospital(hospital);
        definition.setActive(true);
        live.add(definition);
        return definition;
    }

    @Test
    @DisplayName("an unlinked lab line takes the one live test of its code, or of its code after the prefix")
    void linksByCode() {
        PriceListItem gly = line("LAB-GLY", PriceListCategory.LAB_TEST);
        PriceListItem nfs = line("TWP-NFS", PriceListCategory.LAB_TEST);
        LabTestDefinition glycemie = test("TWP-GLY");
        LabTestDefinition numeration = test("TWP-NFS");

        PriceListAutoLinkReportDTO report = autoLink.link(hospitalId, actor);

        assertThat(gly.getLinkType()).isEqualTo(PriceListLinkType.LAB_TEST_DEFINITION);
        assertThat(gly.getLinkKey()).isEqualTo(glycemie.getId().toString());
        assertThat(nfs.getLinkKey()).isEqualTo(numeration.getId().toString());
        assertThat(gly.getUpdatedBy()).isEqualTo(actor);
        assertThat(report.getLinked()).extracting(PriceListAutoLinkReportDTO.Linked::getCode)
            .containsExactlyInAnyOrder("LAB-GLY", "TWP-NFS");
        assertThat(report.getUnmatched()).isEmpty();
    }

    @Test
    @DisplayName("no match, two matches or a test already linked: the line is left as it is and reported")
    void neverGuesses() {
        PriceListItem none = line("LAB-CRP", PriceListCategory.LAB_TEST);
        PriceListItem two = line("LAB-GE", PriceListCategory.LAB_TEST);
        PriceListItem taken = line("LAB-ECBU", PriceListCategory.LAB_TEST);
        test("TWP-GE");
        test("ABC-GE");
        LabTestDefinition ecbu = test("TWP-ECBU");
        PriceListItem holder = PriceListItem.builder().hospitalId(hospitalId).code("OLD-ECBU").build();
        holder.setId(UUID.randomUUID());
        when(items.findActiveByLink(hospitalId, PriceListLinkType.LAB_TEST_DEFINITION, ecbu.getId().toString()))
            .thenReturn(List.of(holder));

        PriceListAutoLinkReportDTO report = autoLink.link(hospitalId, actor);

        assertThat(List.of(none, two, taken)).allSatisfy(item -> assertThat(item.getLinkType()).isNull());
        assertThat(report.getUnmatched()).containsExactly("LAB-CRP");
        assertThat(report.getAmbiguous()).containsExactly("LAB-GE");
        assertThat(report.getAlreadyTaken()).containsExactly("LAB-ECBU");
        verify(items, never()).save(any());
    }

    @Test
    @DisplayName("an exact code wins over a prefix match; a non-lab line, a linked line or an inactive line is untouched")
    void exactWinsAndOnlyUnlinkedLabLines() {
        PriceListItem exact = line("TWP-GLY", PriceListCategory.LAB_TEST);
        PriceListItem consultation = line("CS-GLY", PriceListCategory.CONSULTATION);
        PriceListItem inactive = line("LAB-NFS", PriceListCategory.LAB_TEST);
        inactive.setActive(false);
        LabTestDefinition same = test("TWP-GLY");
        test("ABC-GLY");
        test("TWP-NFS");

        PriceListAutoLinkReportDTO report = autoLink.link(hospitalId, actor);

        assertThat(exact.getLinkKey()).isEqualTo(same.getId().toString());
        assertThat(consultation.getLinkType()).isNull();
        assertThat(inactive.getLinkType()).isNull();
        assertThat(report.getLinked()).hasSize(1);
    }

    @Test
    @DisplayName("the code rule itself: case, spaces and punctuation fold; a single-segment code has no stem")
    void codeRule() {
        assertThat(LabPriceAutoLink.norm(" lab gly ")).isEqualTo("LAB-GLY");
        assertThat(LabPriceAutoLink.norm("LAB_TDR.PALU")).isEqualTo("LAB-TDR-PALU");
        assertThat(LabPriceAutoLink.stem("LAB-TDR-PALU")).isEqualTo("TDR-PALU");
        assertThat(LabPriceAutoLink.stem("GLY")).isNull();
    }
}
