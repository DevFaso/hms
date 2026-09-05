package com.example.hms.service.pro;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
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
import com.example.hms.security.SecurityUtils;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Instrument definitions (Tier 2 item 47): rendering one in a language, and
 * loading one from its validated source.
 *
 * <p>Import replaces the instrument's items, options and texts wholesale
 * and is validated structurally — every item has options, every language
 * covers every item with one label per option, the critical item exists.
 * What it cannot validate is the content itself; that is why the caller is
 * SUPER_ADMIN only and the citation is mandatory.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProInstrumentService {

    public static final String DEFAULT_LANGUAGE = "en";

    private final ProInstrumentRepository instrumentRepository;
    private final ProInstrumentTextRepository textRepository;
    private final AuditEventLogService auditService;
    private final RoleValidator roleValidator;

    @Transactional(readOnly = true)
    public ProInstrument requireActive(String code) {
        return instrumentRepository.findByCodeAndActiveTrue(normalizeCode(code))
            .orElseThrow(() -> new ResourceNotFoundException("Instrument not found: " + code));
    }

    @Transactional(readOnly = true)
    public List<ProInstrument> listActive() {
        return instrumentRepository.findByActiveTrueOrderByCodeAsc();
    }

    @Transactional(readOnly = true)
    public List<String> languagesOf(ProInstrument instrument) {
        return textRepository.findLanguages(instrument.getId());
    }

    /** Active AND has text loaded — the two things an instrument needs before anyone can be offered it. */
    @Transactional(readOnly = true)
    public boolean isAvailable(String code) {
        return instrumentRepository.findByCodeAndActiveTrue(normalizeCode(code))
            .map(i -> !textRepository.findLanguages(i.getId()).isEmpty())
            .orElse(false);
    }

    /**
     * The instrument in {@code language}, falling back to English and then
     * to whatever language it has, so a request for a language the
     * instrument was never translated into still gets a usable form —
     * and says so through {@code language} in the result.
     */
    @Transactional(readOnly = true)
    public ProInstrumentViewDTO render(String code, String language) {
        ProInstrument instrument = requireActive(code);
        List<String> available = textRepository.findLanguages(instrument.getId());
        if (available.isEmpty()) {
            throw new BusinessException("Instrument " + instrument.getCode() + " has no text loaded.");
        }
        String served = pickLanguage(language, available);
        Map<String, String> texts = new HashMap<>();
        for (ProInstrumentText text : textRepository.findByInstrument_IdAndLanguage(instrument.getId(), served)) {
            texts.put(key(text.getItemNo(), text.getOptionNo()), text.getText());
        }

        List<ProInstrumentViewDTO.Item> items = new ArrayList<>();
        for (ProInstrumentItem item : instrument.getItems()) {
            List<ProInstrumentViewDTO.Option> options = new ArrayList<>();
            for (ProInstrumentOption option : item.getOptions()) {
                options.add(ProInstrumentViewDTO.Option.builder()
                    .optionNo(option.getOptionNo())
                    .label(texts.get(key(item.getItemNo(), option.getOptionNo())))
                    .build());
            }
            items.add(ProInstrumentViewDTO.Item.builder()
                .itemNo(item.getItemNo())
                .prompt(texts.get(key(item.getItemNo(), ProInstrumentText.PROMPT)))
                .options(options)
                .build());
        }
        return ProInstrumentViewDTO.builder()
            .code(instrument.getCode())
            .name(instrument.getName())
            .version(instrument.getVersion())
            .sourceCitation(instrument.getSourceCitation())
            .licenceNote(instrument.getLicenceNote())
            .language(served)
            .availableLanguages(available)
            .instruction(texts.get(key(ProInstrumentText.INSTRUMENT_LEVEL, ProInstrumentText.PROMPT)))
            .maxScore(instrument.getMaxScore())
            .criticalItemNo(instrument.getCriticalItemNo())
            .items(items)
            .build();
    }

    /** Create or replace the instrument named by {@code definition.code}. */
    @Transactional
    public ProInstrumentViewDTO importDefinition(ProInstrumentDefinitionDTO definition) {
        validateStructure(definition);
        String code = normalizeCode(definition.getCode());
        ProInstrument instrument = instrumentRepository.findByCode(code)
            .orElseGet(() -> ProInstrument.builder().code(code).build());
        boolean created = instrument.getId() == null;

        instrument.setName(definition.getName().trim());
        instrument.setVersion(trimToNull(definition.getVersion()));
        instrument.setSourceCitation(definition.getSourceCitation().trim());
        instrument.setLicenceNote(trimToNull(definition.getLicenceNote()));
        instrument.setPositiveThreshold(definition.getPositiveThreshold());
        instrument.setCriticalItemNo(definition.getCriticalItemNo());
        instrument.setActive(definition.isActive());
        instrument.setMaxScore(maxScoreOf(definition));

        // Replace, not merge: orphanRemoval drops the old rows. Option
        // scores from a superseded version must not survive next to new ones.
        instrument.getItems().clear();
        instrument.getTexts().clear();
        for (ProInstrumentDefinitionDTO.Item itemDef : definition.getItems()) {
            ProInstrumentItem item = ProInstrumentItem.builder()
                .instrument(instrument)
                .itemNo(itemDef.getItemNo())
                .build();
            for (ProInstrumentDefinitionDTO.Option optionDef : itemDef.getOptions()) {
                item.getOptions().add(ProInstrumentOption.builder()
                    .item(item)
                    .optionNo(optionDef.getOptionNo())
                    .score(optionDef.getScore())
                    .build());
            }
            instrument.getItems().add(item);
        }
        for (ProInstrumentDefinitionDTO.Translation translation : definition.getTexts()) {
            String language = normalizeLanguage(translation.getLanguage());
            String instruction = trimToNull(translation.getInstruction());
            if (instruction != null) {
                instrument.getTexts().add(text(instrument, language,
                    ProInstrumentText.INSTRUMENT_LEVEL, ProInstrumentText.PROMPT, instruction));
            }
            for (ProInstrumentDefinitionDTO.ItemText itemText : translation.getItems()) {
                instrument.getTexts().add(text(instrument, language,
                    itemText.getItemNo(), ProInstrumentText.PROMPT, itemText.getPrompt().trim()));
                List<String> labels = itemText.getOptions();
                for (int i = 0; i < labels.size(); i++) {
                    instrument.getTexts().add(text(instrument, language,
                        itemText.getItemNo(), i + 1, labels.get(i).trim()));
                }
            }
        }
        ProInstrument saved = instrumentRepository.saveAndFlush(instrument);
        emitAudit(saved, created ? "PRO instrument created" : "PRO instrument replaced");
        log.info("PRO instrument {} {} ({} items, {} languages)",
            saved.getCode(), created ? "created" : "replaced",
            definition.getItems().size(), definition.getTexts().size());
        return render(saved.getCode(), DEFAULT_LANGUAGE);
    }

    /**
     * Structural checks the bean validator cannot express: cross-references
     * between items, options and every language's text.
     */
    static void validateStructure(ProInstrumentDefinitionDTO definition) {
        Map<Integer, Integer> optionCounts = new HashMap<>();
        for (ProInstrumentDefinitionDTO.Item item : definition.getItems()) {
            if (optionCounts.put(item.getItemNo(), item.getOptions().size()) != null) {
                throw new BusinessException("Item " + item.getItemNo() + " is defined twice.");
            }
            Set<Integer> optionNos = new HashSet<>();
            for (ProInstrumentDefinitionDTO.Option option : item.getOptions()) {
                if (!optionNos.add(option.getOptionNo())) {
                    throw new BusinessException("Item " + item.getItemNo() + " defines option "
                        + option.getOptionNo() + " twice.");
                }
            }
            for (int expected = 1; expected <= item.getOptions().size(); expected++) {
                if (!optionNos.contains(expected)) {
                    throw new BusinessException("Item " + item.getItemNo()
                        + " options must be numbered 1.." + item.getOptions().size() + " without gaps.");
                }
            }
        }
        Integer critical = definition.getCriticalItemNo();
        if (critical != null && !optionCounts.containsKey(critical)) {
            throw new BusinessException("Critical item " + critical + " is not one of the instrument's items.");
        }
        Set<String> languages = new HashSet<>();
        for (ProInstrumentDefinitionDTO.Translation translation : definition.getTexts()) {
            String language = normalizeLanguage(translation.getLanguage());
            if (!languages.add(language)) {
                throw new BusinessException("Language " + language + " is given twice.");
            }
            Set<Integer> covered = new HashSet<>();
            for (ProInstrumentDefinitionDTO.ItemText itemText : translation.getItems()) {
                Integer expectedOptions = optionCounts.get(itemText.getItemNo());
                if (expectedOptions == null) {
                    throw new BusinessException("Language " + language + " has text for item "
                        + itemText.getItemNo() + ", which the instrument does not define.");
                }
                if (!covered.add(itemText.getItemNo())) {
                    throw new BusinessException("Language " + language + " gives item "
                        + itemText.getItemNo() + " twice.");
                }
                if (itemText.getOptions().size() != expectedOptions) {
                    throw new BusinessException("Language " + language + " item " + itemText.getItemNo()
                        + " has " + itemText.getOptions().size() + " option labels; the item has "
                        + expectedOptions + " options.");
                }
            }
            if (covered.size() != optionCounts.size()) {
                throw new BusinessException("Language " + language + " covers " + covered.size()
                    + " of " + optionCounts.size() + " items; every language must cover every item.");
            }
        }
    }

    private static int maxScoreOf(ProInstrumentDefinitionDTO definition) {
        int max = 0;
        for (ProInstrumentDefinitionDTO.Item item : definition.getItems()) {
            int itemMax = 0;
            for (ProInstrumentDefinitionDTO.Option option : item.getOptions()) {
                itemMax = Math.max(itemMax, option.getScore());
            }
            max += itemMax;
        }
        return max;
    }

    private static ProInstrumentText text(ProInstrument instrument, String language,
                                          int itemNo, int optionNo, String value) {
        return ProInstrumentText.builder()
            .instrument(instrument)
            .language(language)
            .itemNo(itemNo)
            .optionNo(optionNo)
            .text(value)
            .build();
    }

    private static String pickLanguage(String requested, List<String> available) {
        String wanted = requested == null || requested.isBlank() ? DEFAULT_LANGUAGE : normalizeLanguage(requested);
        if (available.contains(wanted)) {
            return wanted;
        }
        // "fr-CA" → "fr"
        int dash = wanted.indexOf('-');
        if (dash > 0 && available.contains(wanted.substring(0, dash))) {
            return wanted.substring(0, dash);
        }
        return available.contains(DEFAULT_LANGUAGE) ? DEFAULT_LANGUAGE : available.get(0);
    }

    static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }

    static String normalizeCode(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    private static String key(int itemNo, int optionNo) {
        return itemNo + ":" + optionNo;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Best-effort: the import is the record; the trail entry must not undo it. */
    private void emitAudit(ProInstrument instrument, String description) {
        try {
            auditService.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.PRO_INSTRUMENT_IMPORTED)
                .status(AuditStatus.SUCCESS)
                .entityType("PRO_INSTRUMENT")
                .resourceId(instrument.getId() != null ? instrument.getId().toString() : null)
                .resourceName(instrument.getCode())
                .userId(roleValidator.getCurrentUserId())
                .userName(SecurityUtils.getCurrentUsername())
                .eventDescription(description + " " + instrument.getCode())
                .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to emit PRO_INSTRUMENT_IMPORTED audit for {}: {}",
                instrument.getCode(), ex.getMessage());
        }
    }
}
