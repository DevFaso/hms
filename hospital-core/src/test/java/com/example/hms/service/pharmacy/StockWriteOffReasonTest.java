package com.example.hms.service.pharmacy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1243 (round 25) guard: every adjustment reason the portal offers is
 * classified on the server as a write-off (only removes stock) or a two-way
 * correction. A new reason added to the portal without a direction fails
 * here instead of letting a write-off add stock.
 */
class StockWriteOffReasonTest {

    private static final Path PORTAL_FORM = Path.of("..", "hospital-portal", "src", "app", "pharmacy", "stock-adjustment.ts");

    @Test
    void everyPortalReasonCodeHasADirection() throws Exception {
        String src = Files.readString(PORTAL_FORM, StandardCharsets.UTF_8);
        List<String> codes = new ArrayList<>();
        for (String list : List.of("ADJUSTMENT_REASON_CODES = [", "LEGACY_REASON_CODES: Readonly<Record<string, string>> = {")) {
            int start = src.indexOf(list);
            assertThat(start).as("the portal still declares " + list).isNotNegative();
            String body = src.substring(start, src.indexOf(list.endsWith("[") ? "]" : "}", start));
            Matcher m = Pattern.compile("'([A-Z_]+)'|\b([A-Z_]{3,})\s*:").matcher(body);
            while (m.find()) {
                codes.add(m.group(1) != null ? m.group(1) : m.group(2));
            }
        }
        assertThat(codes).contains("DAMAGE", "EXPIRY_WRITE_OFF", "CYCLE_COUNT_VARIANCE");
        assertThat(codes).allSatisfy(code -> assertThat(StockWriteOffReason.isClassified(code))
            .as("adjustment reason %s has no direction (StockWriteOffReason)", code).isTrue());
    }

    @Test
    void writeOffsAreReadInEveryForm() {
        assertThat(StockWriteOffReason.isWriteOff("DAMAGE: Flacon cassé")).isTrue();
        assertThat(StockWriteOffReason.isWriteOff("expiry_write_off")).isTrue();
        assertThat(StockWriteOffReason.isWriteOff("Détérioration")).isTrue();
        assertThat(StockWriteOffReason.isWriteOff("Mise au rebut")).isTrue();
        assertThat(StockWriteOffReason.isWriteOff("pérdida")).isTrue();
        assertThat(StockWriteOffReason.isWriteOff("CYCLE_COUNT_VARIANCE: recount")).isFalse();
        assertThat(StockWriteOffReason.isWriteOff("CONTROLLED_DISCREPANCY")).isFalse();
        assertThat(StockWriteOffReason.isWriteOff("Recount after audit")).isFalse();
        assertThat(StockWriteOffReason.isWriteOff(null)).isFalse();
    }
}
