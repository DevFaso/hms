package com.example.hms.security;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.security.PasswordPolicy.Identity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The password rule, and that every path which stores a password a person typed goes through it.
 */
class PasswordPolicyTest {

    private static final Identity AWA = new Identity(
        "awa.traore", "Awa", "Traoré", "awa.traore@exemple.test", "+22670123456");

    @ParameterizedTest(name = "\"{0}\" is refused as too common or too simple")
    @ValueSource(strings = {
        "aaaaaaaa", "12345678", "password", "Password1", "motdepasse", "azertyuiop", "qwertyuiop",
        "Motdepasse2026!", "soleil123", "abababab", "E-Keneya", "bienvenue1", "PASSWORD", "11111111aa",
    })
    void commonPasswordsAreRefused(String password) {
        assertThat(PasswordPolicy.problemKey(password, AWA)).isEqualTo(PasswordPolicy.TOO_COMMON_KEY);
    }

    @ParameterizedTest(name = "\"{0}\" is built on the account's own details")
    @ValueSource(strings = {
        "awa.traore", "AWA.TRAORE!9", "xTraoréx2026", "awa.traore@exemple", "zz70123456zz", "70 12 34 56 ok",
    })
    void theAccountsOwnDetailsAreRefused(String password) {
        assertThat(PasswordPolicy.problemKey(password, AWA)).isEqualTo(PasswordPolicy.CONTAINS_IDENTITY_KEY);
    }

    @Test
    @DisplayName("shorter than the minimum is refused first")
    void tooShort() {
        assertThat(PasswordPolicy.problemKey("Xk9#qT", AWA)).isEqualTo(PasswordPolicy.TOO_SHORT_KEY);
        assertThat(PasswordPolicy.problemKey(null, AWA)).isEqualTo(PasswordPolicy.TOO_SHORT_KEY);
    }

    @ParameterizedTest(name = "\"{0}\" is accepted")
    @ValueSource(strings = {
        "Xk9#qT2vLm", "trois petits chats gris", "Karité-Mangue-47-Tôle", "vR7!pla9Zq",
    })
    void ordinaryStrongPasswordsPass(String password) {
        assertThat(PasswordPolicy.problemKey(password, AWA)).isNull();
    }

    @Test
    @DisplayName("a short first name is not held against a password, and an account with no details is still checked")
    void edges() {
        assertThat(PasswordPolicy.problemKey("Xk9#awaT2vLm", AWA)).isNull();
        assertThat(PasswordPolicy.problemKey("password", new Identity(null, null, null, null, null)))
            .isEqualTo(PasswordPolicy.TOO_COMMON_KEY);
        assertThat(PasswordPolicy.problemKey("Xk9#qT2vLm", null)).isNull();
    }

    @Test
    @DisplayName("the refusal names the field, with a sentence from the bundle")
    void theRefusalNamesTheField() {
        assertThatThrownBy(() -> PasswordPolicy.require("newPassword", "motdepasse", AWA))
            .isInstanceOf(FieldBusinessException.class)
            .satisfies(refusal -> assertThat(((FieldBusinessException) refusal).getField()).isEqualTo("newPassword"));
    }

    @Test
    @DisplayName("the three messages exist in French, English and Spanish")
    void messagesInEveryLanguage() throws IOException {
        for (String suffix : List.of("", "_en", "_fr", "_es")) {
            String bundle = Files.readString(
                Path.of("src", "main", "resources", "i18n", "messages-auth" + suffix + ".properties"),
                StandardCharsets.UTF_8);
            assertThat(bundle).as("messages-auth" + suffix)
                .contains(PasswordPolicy.TOO_SHORT_KEY + "=")
                .contains(PasswordPolicy.TOO_COMMON_KEY + "=")
                .contains(PasswordPolicy.CONTAINS_IDENTITY_KEY + "=");
        }
    }

    /** Hashes that are not a person's password: one-time codes, and the development seed. */
    private static final Set<String> NOT_A_TYPED_PASSWORD = Set.of(
        "MfaService.java", "OwnEmailChangeService.java", "UserCredentialLifecycleServiceImpl.java",
        "DevSyntheticDataSeeder.java");

    private static final Pattern METHOD_START = Pattern.compile(
        "(?m)^    (?:public|private|protected)[^;=\\n]*\\([^;]*?\\)[^;{]*\\{");

    @Test
    @DisplayName("every method that hashes a password also applies the rule")
    void everyPathThatSetsAPasswordAppliesTheRule() throws IOException {
        List<String> unchecked = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (NOT_A_TYPED_PASSWORD.contains(file.getFileName().toString())) {
                    continue;
                }
                String text = Files.readString(file, StandardCharsets.UTF_8);
                int at = text.indexOf("passwordEncoder.encode(");
                while (at >= 0) {
                    String method = enclosingMethod(text, at);
                    if (!method.contains("PasswordPolicy.")) {
                        unchecked.add(file.getFileName() + ": " + method.lines().findFirst().orElse("").trim());
                    }
                    at = text.indexOf("passwordEncoder.encode(", at + 1);
                }
            }
        }
        assertThat(unchecked)
            .as("a method that stores a password a person typed must call PasswordPolicy.require")
            .isEmpty();
    }

    /** From the last method declaration before {@code at} to the next one (or the end of the file). */
    private static String enclosingMethod(String text, int at) {
        Matcher starts = METHOD_START.matcher(text);
        int start = 0;
        int end = text.length();
        while (starts.find()) {
            if (starts.start() <= at) {
                start = starts.start();
            } else {
                end = starts.start();
                break;
            }
        }
        return text.substring(start, end);
    }
}
