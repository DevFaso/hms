package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import com.example.hms.utility.ActivationLinks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scorecard K4, the account links class: a link that is e-mailed or texted (activation, password reset) carries
 * one opaque, single-use, short-lived token in its URL fragment and nothing else. A query string reaches the
 * access log of whatever serves the page, the browser history and a {@code Referer}; a fragment does not leave
 * the browser. So:
 * <ul>
 *   <li>no code builds a URL with a token, an address, a code, a username, a phone number or a password in its
 *       query string;</li>
 *   <li>the portal's account pages ({@code /verify}, {@code /reset-password}) are linked only from
 *       {@link ActivationLinks}, which writes {@code #token=} and no query;</li>
 *   <li>no message template carries such a query string of its own;</li>
 *   <li>an activation token is kept on the account as its hash ({@code ActivationTokens}), never as sent.</li>
 * </ul>
 * A new link goes through {@link ActivationLinks}; the page it lands on reads the fragment and exchanges the
 * token by POST.
 */
@DisplayName("Account links carry one token in the fragment, never a query string (K4)")
class AccountLinkGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path BUNDLES = Path.of("src/main/resources/i18n");

    /** A string literal that puts a secret or an identity in a query string. */
    private static final Pattern SECRET_IN_QUERY = Pattern.compile(
        "\"[^\"\\n]*[?&](?:token|email|code|username|phone|password|confirmationCode)=");

    /** A literal link to one of the portal's account pages. */
    private static final Pattern ACCOUNT_PAGE_LINK = Pattern.compile("\"/(?:verify|reset-password)[?#/][^\"\\n]*\"");

    /** Where an activation token is put on an account. */
    private static final Pattern SETS_ACTIVATION_TOKEN = Pattern.compile("\\.setActivationToken\\s*\\(([^;]*)\\)\\s*;");

    @Test
    @DisplayName("no code builds a URL with a token, an address or a code in its query string")
    void noSecretInAQueryString() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaSources()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = SECRET_IN_QUERY.matcher(source);
            while (m.find()) {
                offenders.add(MAIN.relativize(file) + ":" + SourceScan.lineOf(source, m.start()));
            }
        }
        assertThat(offenders).as("a URL built with a token, an e-mail address, a code, a username or a phone "
            + "number in its query string: an account link carries one opaque token in the fragment "
            + "(ActivationLinks), and the page exchanges it by POST").isEmpty();
    }

    @Test
    @DisplayName("the portal's account pages are linked only from ActivationLinks, with the token in the fragment")
    void accountPagesAreLinkedFromOnePlace() throws IOException {
        List<String> elsewhere = new ArrayList<>();
        List<String> shapes = new ArrayList<>();
        for (Path file : javaSources()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = ACCOUNT_PAGE_LINK.matcher(source);
            while (m.find()) {
                if (file.getFileName().toString().equals("ActivationLinks.java")) {
                    shapes.add(m.group());
                } else {
                    elsewhere.add(MAIN.relativize(file) + ":" + SourceScan.lineOf(source, m.start()));
                }
            }
        }
        assertThat(elsewhere).as("an account link built outside ActivationLinks").isEmpty();
        assertThat(shapes).as("the links ActivationLinks writes")
            .containsExactlyInAnyOrder("\"/verify#token=\"", "\"/reset-password#token=\"");
    }

    @Test
    @DisplayName("the links as built: no query string, no address, the token alone after the #")
    void theLinksAsBuilt() {
        for (String link : List.of(ActivationLinks.activation("https://portal.example", "tok_1-A"),
            ActivationLinks.passwordReset("https://portal.example", "tok_1-A"))) {
            URI uri = URI.create(link);
            assertThat(uri.getQuery()).as(link).isNull();
            assertThat(uri.getFragment()).as(link).isEqualTo("token=tok_1-A");
            assertThat(link).doesNotContain("@", "?", "&");
        }
    }

    @Test
    @DisplayName("no message template carries a query string with a token, an address or a code")
    void noTemplateCarriesSuchAQueryString() throws IOException {
        Pattern inTemplate = Pattern.compile("[?&](?:token|email|code|username|phone|password)=");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(BUNDLES)) {
            for (Path bundle : files.filter(f -> f.getFileName().toString().endsWith(".properties")).sorted().toList()) {
                Properties properties = new Properties();
                try (var reader = Files.newBufferedReader(bundle, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                for (String key : properties.stringPropertyNames()) {
                    if (inTemplate.matcher(properties.getProperty(key)).find()) {
                        offenders.add(bundle.getFileName() + " " + key);
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("an activation token is put on an account as its hash, or cleared")
    void activationTokensAreStoredHashed() throws IOException {
        List<String> offenders = new ArrayList<>();
        int seen = 0;
        for (Path file : javaSources()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = SETS_ACTIVATION_TOKEN.matcher(source);
            while (m.find()) {
                seen++;
                String argument = m.group(1).trim();
                if (!argument.equals("null") && !argument.endsWith(".stored()")) {
                    offenders.add(MAIN.relativize(file) + ":" + SourceScan.lineOf(source, m.start()));
                }
            }
        }
        assertThat(seen).as("places that set an activation token").isGreaterThanOrEqualTo(4);
        assertThat(offenders).as("an activation token stored as it is sent: store ActivationTokens.issue()."
            + "stored() and put only raw() in the link").isEmpty();
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }
}
