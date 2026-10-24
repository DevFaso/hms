package com.example.hms.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * #937 round 1: a signed prescription cannot be edited (D660 sends updates to withdrawal), so the
 * controlled-quantity refusal must tell the pharmacist to have it withdrawn and re-prescribed,
 * never to "add" a quantity to it.
 */
class ControlledQuantityMessageTest {

    @ParameterizedTest
    @CsvSource({
        "messages-pharmacy.properties, withdraw",
        "messages-pharmacy_en.properties, withdraw",
        "messages-pharmacy_fr.properties, retirer",
        "messages-pharmacy_es.properties, retire"
    })
    void messageNamesTheWithdrawAndReprescribePath(String bundle, String verb) throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/i18n/" + bundle)) {
            assertThat(in).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        String msg = p.getProperty("pharmacy.dispense.quantityUnknownControlled");
        assertThat(msg).isNotBlank().containsIgnoringCase(verb);
    }
}
