package com.example.hms.service.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AuditEventType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.pro.ProInstrument;
import com.example.hms.model.pro.ProInstrumentItem;
import com.example.hms.model.pro.ProInstrumentOption;
import com.example.hms.model.pro.ProInstrumentText;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pro.ProInstrumentDefinitionDTO;
import com.example.hms.payload.dto.pro.ProInstrumentViewDTO;
import com.example.hms.repository.pro.ProInstrumentRepository;
import com.example.hms.repository.pro.ProInstrumentTextRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Fixture is a two-item made-up instrument, not the EPDS: the tests are
 * about structure and fallbacks, and instrument content must not live in
 * code.
 */
@ExtendWith(MockitoExtension.class)
class ProInstrumentServiceTest {

    @Mock private ProInstrumentRepository instrumentRepository;
    @Mock private ProInstrumentTextRepository textRepository;
    @Mock private AuditEventLogService auditService;
    @Mock private RoleValidator roleValidator;

    @InjectMocks private ProInstrumentService service;

    // ── fixture builders ──────────────────────────────────────────────

    private static ProInstrumentDefinitionDTO.Item item(int itemNo, int... scores) {
        List<ProInstrumentDefinitionDTO.Option> options = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            options.add(ProInstrumentDefinitionDTO.Option.builder().optionNo(i + 1).score(scores[i]).build());
        }
        return ProInstrumentDefinitionDTO.Item.builder().itemNo(itemNo).options(options).build();
    }

    private static ProInstrumentDefinitionDTO.ItemText itemText(int itemNo, String prompt, String... labels) {
        return ProInstrumentDefinitionDTO.ItemText.builder()
            .itemNo(itemNo).prompt(prompt).options(List.of(labels)).build();
    }

    private static ProInstrumentDefinitionDTO.Translation translation(String language, String instruction,
                                                                     ProInstrumentDefinitionDTO.ItemText... items) {
        return ProInstrumentDefinitionDTO.Translation.builder()
            .language(language).instruction(instruction).items(List.of(items)).build();
    }

    /** Two items: item 1 scores 0..2 (3 options), item 2 scores 0..3 reversed (4 options); item 2 critical. */
    private static ProInstrumentDefinitionDTO definition() {
        return ProInstrumentDefinitionDTO.builder()
            .code("test")
            .name("Test instrument")
            .version("1")
            .sourceCitation("Fixture (not a real instrument)")
            .positiveThreshold(3)
            .criticalItemNo(2)
            .items(new ArrayList<>(List.of(item(1, 0, 1, 2), item(2, 3, 2, 1, 0))))
            .texts(new ArrayList<>(List.of(
                translation("EN", "Answer about the last week.",
                    itemText(1, "Item one?", "never", "sometimes", "often"),
                    itemText(2, "Item two?", "always", "often", "sometimes", "never")),
                translation("fr", null,
                    itemText(1, "Premier item ?", "jamais", "parfois", "souvent"),
                    itemText(2, "Deuxieme item ?", "toujours", "souvent", "parfois", "jamais")))))
            .build();
    }

    private static ProInstrument storedInstrument() {
        ProInstrument instrument = ProInstrument.builder()
            .code("TEST").name("Test instrument").maxScore(5).positiveThreshold(3).criticalItemNo(2).build();
        instrument.setId(UUID.randomUUID());
        ProInstrumentItem one = ProInstrumentItem.builder().instrument(instrument).itemNo(1).build();
        for (int i = 1; i <= 3; i++) {
            one.getOptions().add(ProInstrumentOption.builder().item(one).optionNo(i).score(i - 1).build());
        }
        ProInstrumentItem two = ProInstrumentItem.builder().instrument(instrument).itemNo(2).build();
        for (int i = 1; i <= 2; i++) {
            two.getOptions().add(ProInstrumentOption.builder().item(two).optionNo(i).score(2 - i).build());
        }
        instrument.getItems().add(one);
        instrument.getItems().add(two);
        return instrument;
    }

    private static ProInstrumentText text(ProInstrument instrument, String language, int itemNo, int optionNo,
                                          String value) {
        return ProInstrumentText.builder()
            .instrument(instrument).language(language).itemNo(itemNo).optionNo(optionNo).text(value).build();
    }

    private static List<ProInstrumentText> englishTexts(ProInstrument instrument) {
        return List.of(
            text(instrument, "en", 0, 0, "Instruction"),
            text(instrument, "en", 1, 0, "Item one?"),
            text(instrument, "en", 1, 1, "never"),
            text(instrument, "en", 1, 2, "sometimes"),
            text(instrument, "en", 1, 3, "often"),
            text(instrument, "en", 2, 0, "Item two?"),
            text(instrument, "en", 2, 1, "yes"),
            text(instrument, "en", 2, 2, "no"));
    }

    // ── validateStructure ─────────────────────────────────────────────

    @Nested
    class ValidateStructure {

        @Test
        void acceptsAWellFormedDefinition() {
            ProInstrumentService.validateStructure(definition());
        }

        @Test
        void rejectsAnItemDefinedTwice() {
            ProInstrumentDefinitionDTO def = definition();
            def.getItems().add(item(1, 0, 1));

            assertThatThrownBy(() -> ProInstrumentService.validateStructure(def))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Item 1 is defined twice");
        }

        @Test
        void rejectsDuplicateOptionNumbers() {
            ProInstrumentDefinitionDTO def = definition();
            def.getItems().get(0).getOptions().get(1).setOptionNo(1);

            assertThatThrownBy(() -> ProInstrumentService.validateStructure(def))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("defines option 1 twice");
        }

        @Test
        void rejectsOptionNumberingWithGaps() {
            ProInstrumentDefinitionDTO def = definition();
            def.getItems().get(0).getOptions().get(2).setOptionNo(7);

            assertThatThrownBy(() -> ProInstrumentService.validateStructure(def))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("numbered 1..3 without gaps");
        }

        @Test
        void rejectsACriticalItemTheInstrumentDoesNotHave() {
            ProInstrumentDefinitionDTO def = definition();
            def.setCriticalItemNo(10);

            assertThatThrownBy(() -> ProInstrumentService.validateStructure(def))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Critical item 10");
        }

        @Test
        void rejectsALanguageGivenTwiceCaseInsensitively() {
            ProInstrumentDefinitionDTO def = definition();
            def.getTexts().add(translation("En", null,
                itemText(1, "x", "a", "b", "c"), itemText(2, "y", "a", "b", "c", "d")));

            assertThatThrownBy(() -> ProInstrumentService.validateStructure(def))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Language en is given twice");
        }

        @Test
        void rejectsTextForAnUndefinedItem() {
            ProInstrumentDefinitionDTO def = definition();
            def.getTexts().set(1, translation("fr", null,
                itemText(1, "x", "a", "b", "c"), itemText(3, "y", "a", "b", "c", "d")));

            assertThatThrownBy(() -> ProInstrumentService.validateStructure(def))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("item 3, which the instrument does not define");
        }

        @Test
        void rejectsAnItemTextGivenTwiceInOneLanguage() {
            ProInstrumentDefinitionDTO def = definition();
            def.getTexts().set(1, translation("fr", null,
                itemText(1, "x", "a", "b", "c"), itemText(1, "x", "a", "b", "c")));

            assertThatThrownBy(() -> ProInstrumentService.validateStructure(def))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("gives item 1 twice");
        }

        @Test
        void rejectsAnOptionLabelCountMismatch() {
            ProInstrumentDefinitionDTO def = definition();
            def.getTexts().set(1, translation("fr", null,
                itemText(1, "x", "a", "b"), itemText(2, "y", "a", "b", "c", "d")));

            assertThatThrownBy(() -> ProInstrumentService.validateStructure(def))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("has 2 option labels; the item has 3 options");
        }

        @Test
        void rejectsALanguageThatDoesNotCoverEveryItem() {
            ProInstrumentDefinitionDTO def = definition();
            def.getTexts().set(1, translation("fr", null, itemText(1, "x", "a", "b", "c")));

            assertThatThrownBy(() -> ProInstrumentService.validateStructure(def))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("covers 1 of 2 items");
        }
    }

    // ── render ────────────────────────────────────────────────────────

    @Nested
    class Render {

        @Test
        void rendersItemsAndLabelsWithoutScores() {
            ProInstrument instrument = storedInstrument();
            when(instrumentRepository.findByCodeAndActiveTrue("TEST")).thenReturn(Optional.of(instrument));
            when(textRepository.findLanguages(instrument.getId())).thenReturn(List.of("en"));
            when(textRepository.findByInstrument_IdAndLanguage(instrument.getId(), "en"))
                .thenReturn(englishTexts(instrument));

            ProInstrumentViewDTO view = service.render("test", "en");

            assertThat(view.getCode()).isEqualTo("TEST");
            assertThat(view.getLanguage()).isEqualTo("en");
            assertThat(view.getInstruction()).isEqualTo("Instruction");
            assertThat(view.getMaxScore()).isEqualTo(5);
            assertThat(view.getCriticalItemNo()).isEqualTo(2);
            assertThat(view.getItems()).hasSize(2);
            assertThat(view.getItems().get(0).getPrompt()).isEqualTo("Item one?");
            assertThat(view.getItems().get(0).getOptions()).extracting(ProInstrumentViewDTO.Option::getLabel)
                .containsExactly("never", "sometimes", "often");
            assertThat(view.getItems().get(1).getOptions()).extracting(ProInstrumentViewDTO.Option::getOptionNo)
                .containsExactly(1, 2);
        }

        @Test
        void fallsBackFromRegionalVariantToBaseLanguage() {
            ProInstrument instrument = storedInstrument();
            when(instrumentRepository.findByCodeAndActiveTrue("TEST")).thenReturn(Optional.of(instrument));
            when(textRepository.findLanguages(instrument.getId())).thenReturn(List.of("en", "fr"));
            when(textRepository.findByInstrument_IdAndLanguage(instrument.getId(), "fr")).thenReturn(List.of());

            ProInstrumentViewDTO view = service.render("TEST", "fr-CA");

            assertThat(view.getLanguage()).isEqualTo("fr");
            assertThat(view.getAvailableLanguages()).containsExactly("en", "fr");
        }

        @Test
        void fallsBackToEnglishWhenTheLanguageIsNotLoaded() {
            ProInstrument instrument = storedInstrument();
            when(instrumentRepository.findByCodeAndActiveTrue("TEST")).thenReturn(Optional.of(instrument));
            when(textRepository.findLanguages(instrument.getId())).thenReturn(List.of("fr", "en"));
            when(textRepository.findByInstrument_IdAndLanguage(instrument.getId(), "en")).thenReturn(List.of());

            assertThat(service.render("TEST", "es").getLanguage()).isEqualTo("en");
        }

        @Test
        void fallsBackToWhateverIsLoadedWhenEnglishIsNot() {
            ProInstrument instrument = storedInstrument();
            when(instrumentRepository.findByCodeAndActiveTrue("TEST")).thenReturn(Optional.of(instrument));
            when(textRepository.findLanguages(instrument.getId())).thenReturn(List.of("fr"));
            when(textRepository.findByInstrument_IdAndLanguage(instrument.getId(), "fr")).thenReturn(List.of());

            assertThat(service.render("TEST", null).getLanguage()).isEqualTo("fr");
        }

        @Test
        void refusesAnInstrumentWithNoTextLoaded() {
            ProInstrument instrument = storedInstrument();
            when(instrumentRepository.findByCodeAndActiveTrue("TEST")).thenReturn(Optional.of(instrument));
            when(textRepository.findLanguages(instrument.getId())).thenReturn(List.of());

            assertThatThrownBy(() -> service.render("TEST", "en"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no text loaded");
        }

        @Test
        void unknownOrInactiveInstrumentIsNotFound() {
            when(instrumentRepository.findByCodeAndActiveTrue("NOPE")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.render("nope", "en"))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    void isAvailableRequiresBothActiveAndText() {
        ProInstrument instrument = storedInstrument();
        when(instrumentRepository.findByCodeAndActiveTrue("TEST")).thenReturn(Optional.of(instrument));
        when(textRepository.findLanguages(instrument.getId())).thenReturn(List.of());
        assertThat(service.isAvailable("TEST")).isFalse();

        when(textRepository.findLanguages(instrument.getId())).thenReturn(List.of("en"));
        assertThat(service.isAvailable("TEST")).isTrue();

        when(instrumentRepository.findByCodeAndActiveTrue("GONE")).thenReturn(Optional.empty());
        assertThat(service.isAvailable("GONE")).isFalse();
    }

    // ── importDefinition ──────────────────────────────────────────────

    @Nested
    class ImportDefinition {

        @Test
        void createsTheInstrumentWithItemsOptionsTextsAndMaxScore() {
            AtomicReference<ProInstrument> stored = new AtomicReference<>();
            when(instrumentRepository.findByCode("TEST")).thenReturn(Optional.empty());
            when(instrumentRepository.saveAndFlush(any(ProInstrument.class))).thenAnswer(inv -> {
                ProInstrument saved = inv.getArgument(0);
                saved.setId(UUID.randomUUID());
                stored.set(saved);
                return saved;
            });
            // The import ends by rendering what it wrote.
            when(instrumentRepository.findByCodeAndActiveTrue("TEST"))
                .thenAnswer(inv -> Optional.ofNullable(stored.get()));
            lenient().when(textRepository.findLanguages(any())).thenReturn(List.of("en", "fr"));
            lenient().when(textRepository.findByInstrument_IdAndLanguage(any(), any())).thenReturn(List.of());

            service.importDefinition(definition());

            ArgumentCaptor<ProInstrument> captor = ArgumentCaptor.forClass(ProInstrument.class);
            verify(instrumentRepository).saveAndFlush(captor.capture());
            ProInstrument saved = captor.getValue();
            assertThat(saved.getCode()).isEqualTo("TEST");
            assertThat(saved.getMaxScore()).isEqualTo(2 + 3);
            assertThat(saved.getPositiveThreshold()).isEqualTo(3);
            assertThat(saved.getCriticalItemNo()).isEqualTo(2);
            assertThat(saved.getItems()).hasSize(2);
            assertThat(saved.getItems().get(1).getOptions()).extracting(ProInstrumentOption::getScore)
                .containsExactly(3, 2, 1, 0);
            // EN: instruction + 2 prompts + 7 labels; FR: no instruction + 2 prompts + 7 labels.
            assertThat(saved.getTexts()).hasSize(10 + 9);
            assertThat(saved.getTexts()).filteredOn(t -> t.getItemNo() == 0)
                .extracting(ProInstrumentText::getLanguage).containsExactly("en");
            assertThat(saved.getTexts()).filteredOn(t -> t.getLanguage().equals("fr") && t.getItemNo() == 2
                    && t.getOptionNo() == 4)
                .extracting(ProInstrumentText::getText).containsExactly("jamais");

            ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
            verify(auditService).logEvent(audit.capture());
            assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.PRO_INSTRUMENT_IMPORTED);
            assertThat(audit.getValue().getEventDescription()).isEqualTo("PRO instrument created TEST");
        }

        @Test
        void replacesRatherThanMergesAnExistingInstrument() {
            ProInstrument existing = storedInstrument();
            existing.getTexts().add(text(existing, "en", 1, 0, "Old prompt"));
            when(instrumentRepository.findByCode("TEST")).thenReturn(Optional.of(existing));
            when(instrumentRepository.findByCodeAndActiveTrue("TEST")).thenReturn(Optional.of(existing));
            when(instrumentRepository.saveAndFlush(existing)).thenReturn(existing);
            lenient().when(textRepository.findLanguages(any())).thenReturn(List.of("en"));
            lenient().when(textRepository.findByInstrument_IdAndLanguage(any(), any())).thenReturn(List.of());

            service.importDefinition(definition());

            assertThat(existing.getItems()).hasSize(2);
            assertThat(existing.getItems().get(1).getOptions()).hasSize(4);
            assertThat(existing.getTexts()).extracting(ProInstrumentText::getText).doesNotContain("Old prompt");
            assertThat(existing.getMaxScore()).isEqualTo(5);

            ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
            verify(auditService).logEvent(audit.capture());
            assertThat(audit.getValue().getEventDescription()).isEqualTo("PRO instrument replaced TEST");
        }

        @Test
        void structuralErrorsAreRefusedBeforeAnythingIsWritten() {
            ProInstrumentDefinitionDTO def = definition();
            def.setCriticalItemNo(99);

            assertThatThrownBy(() -> service.importDefinition(def)).isInstanceOf(BusinessException.class);
            verify(instrumentRepository, org.mockito.Mockito.never()).saveAndFlush(any());
        }
    }
}
