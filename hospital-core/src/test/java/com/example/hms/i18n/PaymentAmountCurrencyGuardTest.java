package com.example.hms.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D828: a patient's payment refusal read « Le montant du paiement (999 999)
 * dépasse le solde dû (450). », with no currency, while every amount the portal
 * shows reads "450 F CFA"; the pharmacy one wrote the raw code "XOF".
 *
 * <p>The class of paths is every payment refusal whose bundle text carries an
 * amount: the keys under {@code billing.payment.}, {@code pharmacy.payment.} and
 * {@code invoice.amount}. In every language each placeholder is followed by
 * "F CFA", no bundle names the raw XOF code, and the throwing site passes the
 * number itself (so MessageFormat groups it in the caller's language), never a
 * {@code toPlainString()} or {@code String.valueOf} that would print "2250".
 *
 * <p>A key under these prefixes whose placeholder is not an amount would go in
 * {@link #NOT_AMOUNTS} with its reason; today there is none.
 */
class PaymentAmountCurrencyGuardTest {

    private static final Path I18N = Paths.get("src/main/resources/i18n");
    private static final Path MAIN_JAVA = Paths.get("src/main/java");

    private static final Pattern GUARDED_KEY =
        Pattern.compile("^(billing\\.payment\\.|pharmacy\\.payment\\.|invoice\\.amount).*");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");
    private static final Pattern THROW_SITE = Pattern.compile(
        "new\\s+BusinessException\\(\\s*\"((?:billing|pharmacy)\\.payment\\.[^\"]+|invoice\\.amount[^\"]*)\"([^;]*);");

    /** Keys under the guarded prefixes whose placeholders are not amounts. Only shrinks. */
    private static final List<String> NOT_AMOUNTS = List.of();

    @Test
    void everyAmountInAPaymentRefusalIsFollowedByFCfaInEveryLanguage() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (Path bundle : bundles()) {
            Properties props = load(bundle);
            for (String key : props.stringPropertyNames()) {
                if (!GUARDED_KEY.matcher(key).matches() || NOT_AMOUNTS.contains(key)) {
                    continue;
                }
                String value = props.getProperty(key);
                Matcher m = PLACEHOLDER.matcher(value);
                while (m.find()) {
                    checked++;
                    if (!value.startsWith(" F CFA", m.end())) {
                        offenders.add(bundle.getFileName() + " " + key + " {" + m.group(1) + "}: " + value);
                    }
                }
            }
        }
        assertThat(checked).as("the guard must see the payment refusals it protects").isGreaterThan(20);
        assertThat(offenders).as("a payment refusal amount without F CFA").isEmpty();
    }

    @Test
    void noBundleNamesTheRawXofCode() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path bundle : bundles()) {
            Properties props = load(bundle);
            for (String key : props.stringPropertyNames()) {
                if (props.getProperty(key).matches(".*\\bXOF\\b.*")) {
                    offenders.add(bundle.getFileName() + " " + key);
                }
            }
        }
        assertThat(offenders).as("user-facing text writes F CFA, not the ISO code").isEmpty();
    }

    @Test
    void paymentRefusalsPassTheNumberNotItsText() throws IOException {
        List<String> offenders = new ArrayList<>();
        int sites = 0;
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = THROW_SITE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    sites++;
                    String args = m.group(2);
                    if (args.contains("toPlainString") || args.contains("String.valueOf")
                            || args.contains(".toString()")) {
                        offenders.add(file.getFileName() + " " + m.group(1));
                    }
                }
            }
        }
        assertThat(sites).as("the scan must find the payment refusal sites").isGreaterThanOrEqualTo(4);
        assertThat(offenders).as("an amount passed as text is not grouped in the caller's language").isEmpty();
    }

    private static List<Path> bundles() throws IOException {
        try (Stream<Path> files = Files.list(I18N)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".properties")).sorted().toList();
        }
    }

    private static Properties load(Path bundle) throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(bundle, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }
}
