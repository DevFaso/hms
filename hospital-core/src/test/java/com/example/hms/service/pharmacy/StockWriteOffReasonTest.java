package com.example.hms.service.pharmacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    /** A quoted code ('DAMAGE') or a map key (DAMAGED: ...); possessive, so no backtracking. */
    private static final Pattern CODE = Pattern.compile("'([A-Z_]++)'|\\b([A-Z_]{3,}+)\\s*+:");

    private static final Path PORTAL_FORM = Path.of("..", "hospital-portal", "src", "app", "pharmacy", "stock-adjustment.ts");

    @Test
    void everyPortalReasonCodeHasADirection() throws Exception {
        String src = Files.readString(PORTAL_FORM, StandardCharsets.UTF_8);
        List<String> codes = new ArrayList<>();
        for (String list : List.of("ADJUSTMENT_REASON_CODES = [", "LEGACY_REASON_CODES: Readonly<Record<string, string>> = {")) {
            int start = src.indexOf(list);
            assertThat(start).as("the portal still declares " + list).isNotNegative();
            String body = src.substring(start + list.length(), src.indexOf(list.endsWith("[") ? "]" : "}", start));
            Matcher m = CODE.matcher(body);
            while (m.find()) {
                codes.add(m.group(1) != null ? m.group(1) : m.group(2));
            }
        }
        // The map keys (DAMAGED, EXPIRED) were never read before: the pattern
        // held a backspace and a space where \b and \s were meant (Sonar S9358).
        assertThat(codes).contains("DAMAGE", "EXPIRY_WRITE_OFF", "CYCLE_COUNT_VARIANCE", "DAMAGED", "EXPIRED")
            .allSatisfy(code -> assertThat(StockWriteOffReason.isClassified(code))
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

    /**
     * QA D1261 (round 26): "Mise au rebut (péremption)" and "Perte de stock:
     * carton égaré" added stock - the round-25 reader compared the whole text
     * before the colon with a word list. The input space now covered: every
     * write-off word of the three languages, alone, inside a longer reason,
     * before notes, in brackets, any case, with or without accents, singular,
     * plural, masculine and feminine.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "Mise au rebut (péremption)", "Perte de stock: carton égaré", "Perte de stock", "mise au rebut",
        "Produit périmé", "Flacons périmées", "Date de péremption dépassée", "Boîte endommagée", "Lot abîmé",
        "Casse: flacon tombé", "Bris de flacon", "Vol constaté", "Médicament volé", "Pertes", "Stock perdu",
        "Expired stock", "Damaged box: wet", "Lost in transit", "Stolen", "Breakage", "Disposal of expired lots",
        "Written off", "WRITE-OFF", "write off: audit",
        "Producto vencido", "Caja dañada", "Robo en farmacia", "Rotura de ampolla", "Pérdida de stock",
        "Fecha de caducidad", "Unidades caducadas", "Frascos rotos", "Deterioro",
        "DAMAGE", "EXPIRY_WRITE_OFF", "expiry write off"})
    void everyWriteOffFormIsReadInAnyPositionOfTheReason(String reason) {
        assertThat(StockWriteOffReason.isWriteOff(reason)).as(reason).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "CYCLE_COUNT_VARIANCE", "CYCLE_COUNT_VARIANCE: perte apparente", "CONTROLLED_DISCREPANCY: vol suspecté",
        "Recount after audit", "Inventaire annuel", "Correction de saisie", "Recuento", "Volume corrigé",
        "Retour fournisseur annulé", "Ajuste de inventario"})
    void aTwoWayOrNeutralReasonIsNotAWriteOff(String reason) {
        assertThat(StockWriteOffReason.isWriteOff(reason)).as(reason).isFalse();
    }
}
