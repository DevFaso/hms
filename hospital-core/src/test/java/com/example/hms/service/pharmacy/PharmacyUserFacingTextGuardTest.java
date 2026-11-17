package com.example.hms.service.pharmacy;

import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1000 (class guard): the pharmacy and inventory services sent English
 * sentences to a French or Spanish pharmacist - routing reasons stored as
 * "Medication out of stock; placed on back order", sale and claim refusals
 * thrown as "Sale line quantity must be greater than zero". Every text these
 * sources hand to the UI goes through a message key, resolved in the
 * request's locale.
 *
 * <p>The scan reads the sources (not the classes) because the literal is what
 * matters: a refusal ({@code BusinessException}, {@code ResourceNotFoundException},
 * {@code ConflictException}), a {@code MessageUtil.resolve} call or a stored
 * {@code .reason(...)} must name a key that a {@code messages-*} bundle knows,
 * never a sentence and never a concatenation.
 */
class PharmacyUserFacingTextGuardTest {

    private static final Path JAVA = Path.of("src", "main", "java", "com", "example", "hms");
    private static final Path BUNDLES = Path.of("src", "main", "resources", "i18n");

    /** Every source that answers the pharmacy and inventory screens. */
    private static final List<Path> SCANNED = List.of(
            JAVA.resolve("service/pharmacy"),
            JAVA.resolve("controller/pharmacy"),
            JAVA.resolve("service/MedicationCatalogItemService.java"),
            JAVA.resolve("service/PharmacyServiceImpl.java"),
            JAVA.resolve("service/PharmacyDirectoryServiceImpl.java"),
            JAVA.resolve("controller/MedicationCatalogController.java"),
            JAVA.resolve("controller/PharmacyRegistryController.java"));

    private static final Pattern REFUSAL = Pattern.compile(
            "new\\s+(BusinessException|ResourceNotFoundException|ConflictException)\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"(\\s*\\+)?");
    private static final Pattern RESOLVE = Pattern.compile(
            "MessageUtil\\.resolve(?:OrNull|OrRaw)?\\(\\s*(?:[\\w.]+\\s*,\\s*)?\"((?:[^\"\\\\]|\\\\.)*)\"(\\s*\\+)?");
    private static final Pattern STORED_REASON = Pattern.compile(
            "(?:\\.reason\\(\\s*|recordStockTransaction\\([^;\"]*?)\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * Stock-ledger reasons are the audit trail of a movement and are stored as
     * written; the ledger screen ({@code stock-adjustment.ts#reasonLabel})
     * translates each of these shapes, and its spec pins every one of them.
     * A new shape needs both a pattern there and an entry here.
     */
    static final Set<String> LEDGER_REASON_PREFIXES = Set.of(
            "Stock receipt: lot ",
            "Dispense for prescription ",
            "Dispense cancelled — stock returned for prescription ",
            "Prepared fill cancelled — stock returned for prescription ");

    @Test
    void everyTextThePharmacyServicesSendToTheUiIsAKnownMessageKey() throws IOException {
        Set<String> keys = bundleKeys();
        List<String> offences = new ArrayList<>();
        for (Path root : SCANNED) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    scan(file, Files.readString(file, StandardCharsets.UTF_8), keys, offences);
                }
            }
        }
        assertThat(offences)
                .as("pharmacy/inventory texts that reach the UI without a message key (QA D1000)")
                .isEmpty();
    }

    @Test
    void theScanCatchesASentenceAConcatenationAndAnUnknownKey() {
        Set<String> keys = Set.of("stock.routing.reason.print");
        List<String> offences = new ArrayList<>();
        scan(Path.of("Sample.java"), String.join("\n",
                "throw new BusinessException(\"Sale line quantity must be greater than zero\");",
                "throw new BusinessException(\"Cannot mark \" + verb);",
                "throw new BusinessException(\"stock.unknown.key\");",
                ".reason(\"Medication out of stock; placed on back order\")",
                ".reason(\"stock.routing.reason.print\")",
                ".reason(\"Dispense for prescription \" + id)"), keys, offences);
        assertThat(offences).hasSize(4);
    }

    /**
     * The SMS dispatch path ({@code PrescriptionSmsDispatchServiceImpl}, outside
     * this package) still composes a routing reason in English; the routing
     * mapper renders it through {@link com.example.hms.mapper.pharmacy.RoutingReasonText},
     * which knows each fragment. A fragment it does not know would reach the
     * pharmacist in English again.
     */
    @Test
    void everyEnglishFragmentTheSmsDispatchStoresIsOneTheRoutingMapperTranslates() throws IOException {
        String source = Files.readString(JAVA.resolve("service/impl/PrescriptionSmsDispatchServiceImpl.java"),
                StandardCharsets.UTF_8);
        List<String> composed = new ArrayList<>();
        Matcher append = Pattern.compile("appendReason\\(([^;]*);").matcher(source);
        while (append.find()) {
            composed.add(append.group(1));
        }
        Matcher dispatch = Pattern.compile("String dispatchReason\\([^)]*\\)\\s*\\{(.*?)\\n    }", Pattern.DOTALL)
                .matcher(source);
        assertThat(dispatch.find()).as("dispatchReason(...) found in the SMS dispatch service").isTrue();
        composed.add(dispatch.group(1));
        List<String> unknown = new ArrayList<>();
        for (String code : composed) {
            Matcher literal = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(code);
            while (literal.find()) {
                String text = literal.group(1);
                if (!text.isBlank()
                        && !com.example.hms.mapper.pharmacy.RoutingReasonText.DISPATCH_FRAGMENTS.contains(text)) {
                    unknown.add(text);
                }
            }
        }
        assertThat(unknown).as("routing-reason fragments RoutingReasonText cannot translate (QA D1000)").isEmpty();
    }

    private static void scan(Path file, String source, Set<String> keys, List<String> offences) {
        Matcher refusal = REFUSAL.matcher(source);
        while (refusal.find()) {
            String literal = refusal.group(2);
            if (refusal.group(3) != null) {
                offences.add(where(file, source, refusal.start()) + " concatenated text: \"" + literal + "\"");
            } else if (!isKnownKey(literal, keys)) {
                offences.add(where(file, source, refusal.start()) + " not a known key: \"" + literal + "\"");
            }
        }
        Matcher resolve = RESOLVE.matcher(source);
        while (resolve.find()) {
            String literal = resolve.group(1);
            boolean dynamicSuffix = resolve.group(2) != null;
            // "pharmacy.claim.status." + status.name(): the prefix of known keys.
            if (dynamicSuffix ? keys.stream().noneMatch(k -> k.startsWith(literal) && literal.endsWith("."))
                    : !isKnownKey(literal, keys)) {
                offences.add(where(file, source, resolve.start()) + " resolves an unknown key: \""
                        + resolve.group(1) + "\"");
            }
        }
        Matcher reason = STORED_REASON.matcher(source);
        while (reason.find()) {
            String literal = reason.group(1);
            if (!isKnownKey(literal, keys) && LEDGER_REASON_PREFIXES.stream().noneMatch(literal::equals)) {
                offences.add(where(file, source, reason.start()) + " stores an English reason: \"" + literal + "\"");
            }
        }
    }

    private static boolean isKnownKey(String literal, Set<String> keys) {
        return MessageUtil.isMessageKey(literal) && keys.contains(literal);
    }

    private static String where(Path file, String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return file.getFileName() + ":" + line;
    }

    private static Set<String> bundleKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.list(BUNDLES)) {
            for (Path bundle : files.filter(p -> p.getFileName().toString().matches("messages-[a-z]+\\.properties"))
                    .toList()) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(bundle);
                     java.io.Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
                keys.addAll(props.stringPropertyNames());
            }
        }
        assertThat(keys).as("message keys read from %s", BUNDLES.toAbsolutePath()).hasSizeGreaterThan(500);
        return keys;
    }
}
