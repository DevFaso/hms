package com.example.hms.service.pharmacy;

import com.example.hms.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA D1163 / D1192 (rounds 21 and 22): a fill's unit names the same kind of thing as the lot's. */
class DispenseUnitFormsTest {

    @ParameterizedTest(name = "{0} from a {1} lot is refused")
    @CsvSource({"flacon, comprimé", "flacon, gélule", "Flacons, tablet", "ml, comprimés", "comprimé, flacon",
        // QA D1192 (round 22): the liquid forms the re-test typed, from a tablet lot.
        "sirop, comprimé", "gouttes, comprimé", "Sirop, Comprimés", "suspension, gélule", "jarabe, comprimido",
        "gotas, tablet", "cuillère à café, comprimé", "sirop, ml", "gouttes, flacon",
        // An unknown unit is never accepted across another unit (round 21 accepted "boîte" from a tablet lot:
        // changed assertion, the class of D1192).
        "unité, comprimé", "comprimé, unité", "dose, flacon", "boîte, comprimé"})
    void anotherKindOfUnitIsRefused(String fill, String lot) {
        assertThatThrownBy(() -> DispenseUnitForms.requireCompatible(fill, lot))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("pharmacy.dispense.unitMismatch");
    }

    @ParameterizedTest(name = "{0} from a {1} lot is accepted")
    @CsvSource({"comprimé, tablet", "cp, Comprimés", "gélule, comprimé", "flacon, bottle", "ml, mL",
        "'', comprimé", "comprimé, ''", "sirop, flacon", "flacon, sirop", "Sirop, sirops", "gouttes, goutte",
        "unité, Unité", "boîte, boîtes", "Cuillère à café, cuillere-a-cafe"})
    void theSameKindOrTheLotsOwnUnitIsAccepted(String fill, String lot) {
        assertThatCode(() -> DispenseUnitForms.requireCompatible(fill, lot)).doesNotThrowAnyException();
    }

    /**
     * QA D3949 (r77), review F1: a pack with contents fits only the same pack with the same contents, whatever the
     * case, accents, plurals, spacing or typographic apostrophe.
     */
    @ParameterizedTest(name = "{0} from a {1} lot is accepted")
    @CsvSource({"plaquette de 10 comprimés, Plaquettes de 10 comprimés", "boîte de 24 comprimés, Boites de 24 comprimes",
        "flacon de 100 ml, flacon de 100ml", "flacon de 100 ml, Flacons  de 100 mL", "box of 20 tablets, box of 20 tablet",
        "blister (10 cp), blisters (10 cp)", "plaquette x10, plaquette x 10", "plaquette/10, plaquettes / 10",
        "pot d’30 gélules, pot de 30 gélules", "'flacon de 2,5 ml', flacon de 2.5 ml"})
    void theSamePackWithTheSameContentsFits(String fill, String lot) {
        assertThatCode(() -> DispenseUnitForms.requireCompatible(fill, lot)).doesNotThrowAnyException();
        assertThat(DispenseUnitForms.fits(fill, lot)).isTrue();
    }

    /**
     * Review F1: packs of another size, another pack, another family, or a pack against its contents are refused
     * (develop refused all of these).
     */
    @ParameterizedTest(name = "{0} from a {1} lot is refused")
    @CsvSource({"boîte de 24 comprimés, boîte de 12 comprimés", "flacon de 100 ml, flacon de 250 ml",
        "plaquette de 10 comprimés, sirop", "sirop, plaquette de 10 comprimés", "plaquette de 10 comprimés, flacon",
        "flacon, plaquette de 10 comprimés", "sirop, flacon de 100 ml", "plaquette de 10 comprimés, boîte de 10 comprimés",
        "plaquette de 10 comprimés, plaquette de 10 gélules", "comprimé, plaquette de 10 comprimés",
        "plaquette de 10 comprimés, comprimé", "ml, flacon de 100 ml", "gouttes, flacon de 20 ml",
        "dose, pot de 30 gélules", "blister (10 cp), blister (20 cp)"})
    void anotherPackOrAnotherSizeIsRefused(String fill, String lot) {
        assertThat(DispenseUnitForms.fits(fill, lot)).isFalse();
        assertThatThrownBy(() -> DispenseUnitForms.requireCompatible(fill, lot))
            .isInstanceOf(BusinessException.class);
    }

    /**
     * Review F2: a bare pack never fits a pack with contents, either way, whatever the item's units per pack (which
     * counts units per pack, not what one unit holds). A plaquette of 30 took an order for « 1 plaquette de 10 ».
     */
    @ParameterizedTest(name = "{0} from a {1} lot is refused")
    @CsvSource({"plaquette, plaquette de 10 comprimés", "plaquette de 10 comprimés, plaquette",
        "Plaquettes, plaquette de 10 comprimés", "plaquette de 10 comprimés, Plaquettes", "boîte, boîte de 24 comprimés",
        "boîte de 24 comprimés, boîte", "flacon, flacon de 100 ml", "flacon de 100 ml, flacon",
        "pot, pot de 30 gélules", "blister, blister (10 cp)"})
    void aBarePackNeverFitsAPackWithContents(String fill, String lot) {
        assertThat(DispenseUnitForms.fits(fill, lot)).isFalse();
        assertThatThrownBy(() -> DispenseUnitForms.requireCompatible(fill, lot))
            .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest(name = "\"{0}\" reads as \"{1}\"")
    @CsvSource({"Plaquette de 10 comprimés, plaquette", "Cuillère à café, cuillere a cafe", "solution buvable, solution buvable",
        "(10 cp), (10 cp)", "boîte  de 24, boite", "flacon de, flacon de", "pot d’30 gélules, pot"})
    void theKeyOfAUnit(String unit, String key) {
        assertThat(DispenseUnitForms.key(unit)).isEqualTo(key);
    }

    /** A long typed unit is read in one pass (no backtracking pattern on a typed value). */
    @Test
    void aLongTypedUnitIsReadQuickly() {
        String[] longUnits = {"' ".repeat(5000), "' x0".repeat(5000), "de ".repeat(5000) + "x", "(".repeat(5000)};
        long start = System.nanoTime();
        for (String unit : longUnits) {
            DispenseUnitForms.fits(unit, "plaquette de 10 comprimés");
        }
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(500);
    }

    @Test
    void aBlankUnitFitsAnyLot() {
        assertThat(DispenseUnitForms.fits(null, "comprimé")).isTrue();
        assertThat(DispenseUnitForms.fits("flacon", " ")).isTrue();
    }

    /**
     * The guard: every pair of unit groups is decided here, and only the pairs
     * listed as compatible are. A new group fails until its row is written.
     */
    @Test
    void everyPairOfGroupsIsDecided() {
        Map<DispenseUnitForms.Form, String> sample = new EnumMap<>(DispenseUnitForms.Form.class);
        sample.put(DispenseUnitForms.Form.SOLID_DOSE, "comprimé");
        sample.put(DispenseUnitForms.Form.LIQUID_PREPARATION, "sirop");
        sample.put(DispenseUnitForms.Form.DOSE_MEASURE, "gouttes");
        sample.put(DispenseUnitForms.Form.CONTAINER, "flacon");
        sample.put(DispenseUnitForms.Form.MEASURED, "ml");
        assertThat(sample).as("a sample unit for every group").hasSize(DispenseUnitForms.Form.values().length);

        List<String> compatiblePairs = new ArrayList<>();
        for (DispenseUnitForms.Form fill : DispenseUnitForms.Form.values()) {
            assertThat(DispenseUnitForms.formOf(sample.get(fill))).isEqualTo(fill);
            for (DispenseUnitForms.Form lot : DispenseUnitForms.Form.values()) {
                if (fill != lot && DispenseUnitForms.compatible(sample.get(fill), sample.get(lot))) {
                    compatiblePairs.add(fill + "<-" + lot);
                }
            }
        }
        assertThat(compatiblePairs).containsExactlyInAnyOrder(
            "LIQUID_PREPARATION<-CONTAINER", "CONTAINER<-LIQUID_PREPARATION");
    }
}
