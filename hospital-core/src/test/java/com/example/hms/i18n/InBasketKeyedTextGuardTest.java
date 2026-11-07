package com.example.hms.i18n;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1269 (round 26; the D1053 class): the post-partum haemorrhage In-Basket
 * item was composed in the staff language and stored as prose, so EN and ES
 * doctors read it in French. The item has keys and arguments the reader's
 * In-Basket renders ({@code titleKey}/{@code titleArgs},
 * {@code bodyKey}/{@code bodyArgs}); every producer must fill them.
 *
 * <p>Every main source that builds a {@code CreateInBasketItemRequest} and
 * sets its title sets its title key; one that sets a body sets a body key,
 * unless the body is the user's own words (listed with the reason).
 */
@DisplayName("D1269: every In-Basket producer stores keys, not prose")
class InBasketKeyedTextGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern BUILDS = Pattern.compile("CreateInBasketItemRequest\\.builder\\(\\)|new\\s++CreateInBasketItemRequest\\(\\)"
        + "|new\\s++com\\.example\\.hms\\.payload\\.dto\\.clinical\\.CreateInBasketItemRequest\\(\\)");
    private static final Pattern TITLE = Pattern.compile("\\.(?:title|setTitle)\\(");
    private static final Pattern TITLE_KEY = Pattern.compile("\\.(?:titleKey|setTitleKey)\\(");
    private static final Pattern BODY = Pattern.compile("\\.(?:body|setBody)\\(");
    private static final Pattern BODY_KEY = Pattern.compile("\\.(?:bodyKey|setBodyKey)\\(");

    /** Producers whose body is free text a person typed, never translated, each with its reason. */
    private static final Map<String, String> BODY_IS_USER_TEXT = Map.of(
        "LabSpecimenRejectionNotifier.java", "the body is the rejection reason the lab technician typed");

    @Test
    @DisplayName("an In-Basket producer that sets a title or a body also sets its key")
    void everyProducerStoresKeys() throws IOException {
        List<String> prose = new ArrayList<>();
        int producers = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!BUILDS.matcher(source).find() || file.endsWith("InBasketServiceImpl.java")) {
                    continue;
                }
                producers++;
                String name = file.getFileName().toString();
                if (TITLE.matcher(source).find() && !TITLE_KEY.matcher(source).find()) {
                    prose.add(name + ": a title with no titleKey");
                }
                if (BODY.matcher(source).find() && !BODY_KEY.matcher(source).find()
                        && !BODY_IS_USER_TEXT.containsKey(name)) {
                    prose.add(name + ": a body with no bodyKey");
                }
            }
        }
        assertThat(producers).as("In-Basket producers read").isGreaterThanOrEqualTo(3);
        assertThat(prose)
            .as("stored prose is read in the writer's language by every reader (QA D1269, D1053)" + SourceScan.MANDATE)
            .isEmpty();
    }
}
