package com.example.hms.service.pharmacy;

import com.example.hms.exception.BusinessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA D1163 (round 21): a fill's unit names the same kind of thing as the lot's. */
class DispenseUnitFormsTest {

    @ParameterizedTest(name = "{0} from a {1} lot is refused")
    @CsvSource({"flacon, comprimé", "flacon, gélule", "Flacons, tablet", "ml, comprimés", "comprimé, flacon"})
    void anotherKindOfUnitIsRefused(String fill, String lot) {
        assertThatThrownBy(() -> DispenseUnitForms.requireCompatible(fill, lot))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("pharmacy.dispense.unitMismatch");
    }

    @ParameterizedTest(name = "{0} from a {1} lot is accepted")
    @CsvSource({"comprimé, tablet", "cp, Comprimés", "gélule, comprimé", "flacon, bottle", "ml, mL",
        "boîte, comprimé", "'', comprimé", "comprimé, ''"})
    void theSameKindOrAnUnknownUnitIsAccepted(String fill, String lot) {
        assertThatCode(() -> DispenseUnitForms.requireCompatible(fill, lot)).doesNotThrowAnyException();
    }
}
