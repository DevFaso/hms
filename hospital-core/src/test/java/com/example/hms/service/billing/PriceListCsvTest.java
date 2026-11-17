package com.example.hms.service.billing;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Billing-money plan PR 4: the CSV format on its own (separators, encodings, French number and date formats, header
 * rules, limits, CSV injection), with the reasons in French.
 */
@DisplayName("Price list CSV: the file format")
class PriceListCsvTest {

    private static final String HEADER = "code;libelle;categorie;prix;date_effet;liaison_type;liaison_cle";

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
    }

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("French Excel: ';', a BOM, '2 000' with a non-breaking space, 01/12/2026")
    void frenchExcelUtf8WithBom() {
        PriceListCsv.Parsed parsed = PriceListCsv.parse(utf8("\uFEFF" + HEADER + "\r\n"
            + "CS-GEN;Consultation générale;CONSULTATION;2\u00A0000;01/12/2026;SYSTEM;CONSULTATION_DEFAULT\r\n"
            + ";;;;;;\r\n"));
        assertThat(parsed.separator()).isEqualTo(';');
        assertThat(parsed.charset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(parsed.rows()).hasSize(1);
        PriceListCsv.Row row = parsed.rows().get(0);
        assertThat(row.line()).isEqualTo(2);
        assertThat(row.get("libelle")).isEqualTo("Consultation générale");
        assertThat(PriceListCsv.parsePrice(row.get("prix"))).isEqualByComparingTo("2000");
        assertThat(PriceListCsv.parseDate(row.get("date_effet"))).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    @DisplayName("Windows-1252 (not valid UTF-8) is read with its accents")
    void windows1252() {
        Charset cp1252 = Charset.forName("windows-1252");
        PriceListCsv.Parsed parsed = PriceListCsv.parse((HEADER + "\nIMG-ECHO;Échographie obstétricale;IMAGING;7500;;;\n")
            .getBytes(cp1252));
        assertThat(parsed.charset()).isEqualTo(cp1252);
        assertThat(parsed.rows().get(0).get("libelle")).isEqualTo("Échographie obstétricale");
    }

    @Test
    @DisplayName("',' separator, quoted cells, columns in another order and the optional ones left out")
    void commaSeparatorAndReorderedHeader() {
        PriceListCsv.Parsed parsed = PriceListCsv.parse(utf8("""
            Prix,Code,Libellé,Catégorie
            "2 500",LAB-NFS,"NFS, numération",LAB_TEST
            """));
        assertThat(parsed.separator()).isEqualTo(',');
        PriceListCsv.Row row = parsed.rows().get(0);
        assertThat(row.get("code")).isEqualTo("LAB-NFS");
        assertThat(row.get("libelle")).isEqualTo("NFS, numération");
        assertThat(row.get("prix")).isEqualTo("2 500");
        assertThat(row.get("date_effet")).isEmpty();
        assertThat(row.extraCells()).isFalse();
    }

    @Test
    @DisplayName("a row with more cells than the header is flagged")
    void extraCells() {
        PriceListCsv.Row row = PriceListCsv.parse(utf8(HEADER + "\nA;B;CONSULTATION;1;;;;oops\n")).rows().get(0);
        assertThat(row.extraCells()).isTrue();
    }

    @Test
    void wholeFrancPrices() {
        assertThat(PriceListCsv.parsePrice("2000")).isEqualByComparingTo("2000");
        assertThat(PriceListCsv.parsePrice("2 000")).isEqualByComparingTo("2000");
        assertThat(PriceListCsv.parsePrice("1\u202F250\u00A0000")).isEqualByComparingTo("1250000");
        assertThat(PriceListCsv.parsePrice("2000,00")).isEqualByComparingTo("2000");
        assertThat(PriceListCsv.parsePrice("0")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void refusedPricesNameThePriceColumnInFrench() {
        assertThatThrownBy(() -> PriceListCsv.parsePrice("2500,50")).isInstanceOf(FieldBusinessException.class)
            .hasMessage("Saisissez le prix unitaire en francs CFA entiers, sans décimales.")
            .extracting(e -> ((FieldBusinessException) e).getField()).isEqualTo("prix");
        assertThatThrownBy(() -> PriceListCsv.parsePrice("-5"))
            .hasMessage("Le prix unitaire ne peut pas être négatif.");
        assertThatThrownBy(() -> PriceListCsv.parsePrice(""))
            .hasMessage("Saisissez le prix unitaire.");
        // "2.500" may be two thousand five hundred or two and a half: never guessed.
        assertThatThrownBy(() -> PriceListCsv.parsePrice("2.500"))
            .hasMessageContaining("« 2.500 » n’est pas un prix en francs CFA entiers");
        assertThatThrownBy(() -> PriceListCsv.parsePrice("2 000 F"))
            .hasMessageContaining("n’est pas un prix en francs CFA entiers");
    }

    @Test
    void dates() {
        assertThat(PriceListCsv.parseDate("01/12/2026")).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(PriceListCsv.parseDate("1/2/2027")).isEqualTo(LocalDate.of(2027, 2, 1));
        assertThat(PriceListCsv.parseDate("2026-12-01")).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(PriceListCsv.parseDate(" ")).isNull();
        assertThatThrownBy(() -> PriceListCsv.parseDate("31/02/2026"))
            .hasMessageContaining("« 31/02/2026 » n’est pas une date")
            .extracting(e -> ((FieldBusinessException) e).getField()).isEqualTo("date_effet");
        assertThatThrownBy(() -> PriceListCsv.parseDate("12/01/26")).hasMessageContaining("n’est pas une date");
    }

    @Test
    @DisplayName("a missing, unknown or repeated header column refuses the whole file")
    void headerRules() {
        assertThatThrownBy(() -> PriceListCsv.parse(utf8("code;libelle;categorie\nA;B;CONSULTATION\n")))
            .hasMessageStartingWith("La ligne d’en-tête n’a pas de colonne « prix ».")
            .extracting(e -> ((FieldBusinessException) e).getField()).isEqualTo("file");
        assertThatThrownBy(() -> PriceListCsv.parse(utf8(HEADER + ";tarif\nA;B;CONSULTATION;1;;;;2\n")))
            .hasMessageStartingWith("Colonne inconnue « tarif »");
        assertThatThrownBy(() -> PriceListCsv.parse(utf8("code;code;libelle;categorie;prix\n")))
            .hasMessage("La colonne « code » figure deux fois dans la ligne d’en-tête.");
        assertThatThrownBy(() -> PriceListCsv.parse(utf8(HEADER + "\n\n")))
            .hasMessage("Le fichier contient la ligne d’en-tête mais aucune ligne à importer.");
        assertThatThrownBy(() -> PriceListCsv.parse(new byte[0]))
            .hasMessageStartingWith("Choisissez un fichier CSV");
    }

    @Test
    @DisplayName("more than 5 000 rows or 1 MB refuses the whole file")
    void limits() {
        StringBuilder big = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i <= PriceListCsv.MAX_ROWS; i++) {
            big.append("C").append(i).append(";L;OTHER_SERVICE;1;;;\n");
        }
        assertThatThrownBy(() -> PriceListCsv.parse(utf8(big.toString())))
            .satisfies(e -> assertThat(e.getMessage().replaceAll("[\u00A0\u202F]", " "))
                .isEqualTo("Le fichier compte plus de 5 000 lignes. Découpez la grille en plusieurs fichiers."));
        assertThatThrownBy(() -> PriceListCsv.parse(new byte[PriceListCsv.MAX_BYTES + 1]))
            .hasMessage("Le fichier dépasse 1 Mo. Découpez la grille en plusieurs fichiers.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"=cmd|' /C calc'!A0", "+1", "-2+3", "@SUM(A1)", "\tx"})
    void formulaCellsAreRecognisedAndEscapedOnExport(String cell) {
        assertThat(PriceListCsv.isFormula(cell)).isTrue();
        String exported = new String(PriceListCsv.write(List.of(new PriceListCsv.ExportRow("ADM-X", cell,
            "OTHER_SERVICE", new BigDecimal("100"), LocalDate.of(2026, 12, 1), null, null))), StandardCharsets.UTF_8);
        String line = exported.split("\r\n")[1];
        assertThat(line).doesNotStartWith("=").contains("'" + cell.charAt(0));
    }

    @Test
    @DisplayName("the export is the import's format: BOM, ';', header, whole francs, JJ/MM/AAAA, and reads back")
    void exportReadsBack() {
        byte[] file = PriceListCsv.write(List.of(
            new PriceListCsv.ExportRow("CS-GEN", "Consultation générale; adulte", "CONSULTATION",
                new BigDecimal("2000.00"), LocalDate.of(2026, 12, 1), "SYSTEM", "CONSULTATION_DEFAULT"),
            new PriceListCsv.ExportRow("ADM-CARNET", "Carnet de santé", "OTHER_SERVICE",
                new BigDecimal("500"), LocalDate.of(2026, 12, 1), null, null)));
        String text = new String(file, StandardCharsets.UTF_8);
        assertThat(text).startsWith("\uFEFF" + HEADER + "\r\n")
            .contains("CS-GEN;\"Consultation générale; adulte\";CONSULTATION;2000;01/12/2026;SYSTEM;CONSULTATION_DEFAULT");
        PriceListCsv.Parsed back = PriceListCsv.parse(file);
        assertThat(back.rows()).hasSize(2);
        assertThat(back.rows().get(0).get("libelle")).isEqualTo("Consultation générale; adulte");
        assertThat(back.rows().get(1).get("liaison_type")).isEmpty();
    }

    @Test
    @DisplayName("review #1206: a negative plain number is not a formula; an exported apostrophe reads back")
    void plainNumbersAndUnescape() {
        assertThat(com.example.hms.utility.CsvCells.isFormula("-5")).isFalse();
        assertThat(com.example.hms.utility.CsvCells.isFormula("-2,50")).isFalse();
        assertThat(com.example.hms.utility.CsvCells.isFormula("- Pansement")).isTrue();
        assertThat(com.example.hms.utility.CsvCells.unescape("'- Pansement")).isEqualTo("- Pansement");
        assertThat(com.example.hms.utility.CsvCells.unescape("'Pansement")).isEqualTo("'Pansement");
        PriceListCsv.Row row = PriceListCsv.parse(PriceListCsv.write(List.of(new PriceListCsv.ExportRow("ACT-1",
            "- Pansement", "PROCEDURE", new BigDecimal("1000"), LocalDate.of(2026, 12, 1), null, null))))
            .rows().get(0);
        assertThat(row.get("libelle")).isEqualTo("- Pansement");
    }
}
