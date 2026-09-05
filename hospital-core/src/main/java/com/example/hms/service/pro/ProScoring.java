package com.example.hms.service.pro;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.pro.ProInstrument;
import com.example.hms.model.pro.ProInstrumentItem;
import com.example.hms.model.pro.ProInstrumentOption;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scores one administration of an instrument (Tier 2 item 47).
 *
 * <p>Pure arithmetic over the instrument's own data: sum the score of each
 * chosen option, compare to the instrument's threshold, look at its
 * critical item. Nothing about which options are "bad" lives here — the
 * validated source assigned every option its score, and the engine trusts
 * the rows. Same partial-scoring doctrine as NEWS2: an item left
 * unanswered is named in {@code missingItems} and the result is flagged
 * incomplete rather than refused, because a mother who stopped at item 8
 * still told us something, and the total is then a lower bound.
 */
public final class ProScoring {

    private ProScoring() {
    }

    public record ProScoreResult(
        int totalScore,
        int answeredItems,
        int totalItems,
        List<Integer> missingItems,
        Integer criticalItemScore,
        boolean screenPositive,
        boolean criticalPositive
    ) {
        public boolean complete() {
            return missingItems.isEmpty();
        }
    }

    /**
     * @param answers itemNo → optionNo as the respondent chose them
     * @throws BusinessException on an item or option the instrument does not have
     */
    public static ProScoreResult score(ProInstrument instrument, Map<Integer, Integer> answers) {
        Map<Integer, Integer> safeAnswers = answers == null ? Collections.emptyMap() : answers;
        Map<Integer, ProInstrumentItem> itemsByNo = new TreeMap<>();
        for (ProInstrumentItem item : instrument.getItems()) {
            itemsByNo.put(item.getItemNo(), item);
        }
        if (itemsByNo.isEmpty()) {
            throw new BusinessException("Instrument " + instrument.getCode() + " has no items loaded");
        }
        for (Integer itemNo : safeAnswers.keySet()) {
            if (itemNo == null || !itemsByNo.containsKey(itemNo)) {
                throw new BusinessException("Instrument " + instrument.getCode() + " has no item " + itemNo);
            }
        }

        int total = 0;
        int answered = 0;
        Integer criticalScore = null;
        List<Integer> missing = new ArrayList<>();
        Integer criticalItemNo = instrument.getCriticalItemNo();

        for (Map.Entry<Integer, ProInstrumentItem> entry : itemsByNo.entrySet()) {
            int itemNo = entry.getKey();
            Integer optionNo = safeAnswers.get(itemNo);
            if (optionNo == null) {
                missing.add(itemNo);
                continue;
            }
            int optionScore = resolveOption(instrument, entry.getValue(), optionNo).getScore();
            total += optionScore;
            answered++;
            if (criticalItemNo != null && criticalItemNo == itemNo) {
                criticalScore = optionScore;
            }
        }

        boolean screenPositive = total >= instrument.getPositiveThreshold();
        boolean criticalPositive = criticalScore != null && criticalScore > 0;
        return new ProScoreResult(total, answered, itemsByNo.size(),
            Collections.unmodifiableList(missing), criticalScore, screenPositive, criticalPositive);
    }

    private static ProInstrumentOption resolveOption(ProInstrument instrument, ProInstrumentItem item, int optionNo) {
        for (ProInstrumentOption option : item.getOptions()) {
            if (option.getOptionNo() == optionNo) {
                return option;
            }
        }
        throw new BusinessException("Instrument " + instrument.getCode()
            + " item " + item.getItemNo() + " has no option " + optionNo);
    }
}
