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
