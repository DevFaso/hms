package com.example.hms.service.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantArchiveEncryptionServiceImplTest {

    private TenantArchiveEncryptionServiceImpl serviceWith(MockEnvironment env, String kekSource, String kekBase64) {
        TenantArchiveEncryptionServiceImpl service = new TenantArchiveEncryptionServiceImpl(env);
        ReflectionTestUtils.setField(service, "kekSource", kekSource);
        ReflectionTestUtils.setField(service, "envKekBase64", kekBase64 == null ? "" : kekBase64);
        return service;
    }

    private Path samplePlaintext(Path tmp, String body) throws IOException {
        Path p = tmp.resolve("plain.zip");
        Files.write(p, body.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private String randomKekB64() {
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        return Base64.getEncoder().encodeToString(kek);
    }

    @Test
    void noopModeIsAllowedInDevAndCopiesPlaintext(@TempDir Path tmp) throws IOException {
        MockEnvironment env = new MockEnvironment().withProperty("dummy", "x");
        env.setActiveProfiles("dev");
        TenantArchiveEncryptionServiceImpl service = serviceWith(env, "noop", null);

        Path plain = samplePlaintext(tmp, "hello-world");
        Path out = tmp.resolve("plain.zip.enc");

        TenantArchiveEncryptionService.EncryptionResult result = service.encryptArchive(plain, out);

        assertThat(result.mode())
            .isEqualTo(TenantArchiveEncryptionService.EncryptionResult.Mode.NOOP);
        assertThat(Files.readString(out)).isEqualTo("hello-world");
        assertThat(Files.exists(result.envelopePath())).isTrue();
    }

    @Test
    void noopModeIsRejectedWhenNonDevProfileActive(@TempDir Path tmp) throws IOException {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        TenantArchiveEncryptionServiceImpl service = serviceWith(env, "noop", null);

        Path plain = samplePlaintext(tmp, "should-not-pass");
        Path out = tmp.resolve("denied.zip.enc");

        assertThatThrownBy(() -> service.encryptArchive(plain, out))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("only permitted in dev/test");
    }

    @Test
    void envModeRoundTripsCiphertext(@TempDir Path tmp) throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        String kek = randomKekB64();
        TenantArchiveEncryptionServiceImpl service = serviceWith(env, "env", kek);

        String plaintext = "tenant archive content (would normally be a ZIP)";
        Path plain = samplePlaintext(tmp, plaintext);
        Path out = tmp.resolve("envmode.zip.enc");

        TenantArchiveEncryptionService.EncryptionResult result = service.encryptArchive(plain, out);

        assertThat(result.mode())
            .isEqualTo(TenantArchiveEncryptionService.EncryptionResult.Mode.ENCRYPTED);
        assertThat(result.cipher()).isEqualTo("AES/GCM/NoPadding");

        // Ciphertext must not equal the plaintext.
        byte[] ciphertext = Files.readAllBytes(out);
        assertThat(new String(ciphertext, StandardCharsets.UTF_8)).isNotEqualTo(plaintext);

        // Round-trip via the envelope manifest: unwrap DEK with KEK, decrypt
        // the archive with DEK + data IV.
        JsonNode envelope = new ObjectMapper().readTree(Files.readAllBytes(result.envelopePath()));
        byte[] kekBytes = Base64.getDecoder().decode(kek);
        byte[] wrapIv = Base64.getDecoder().decode(envelope.get("wrap_iv_b64").asText());
        byte[] wrappedDek = Base64.getDecoder().decode(envelope.get("wrapped_dek_b64").asText());
        byte[] dataIv = Base64.getDecoder().decode(envelope.get("data_iv_b64").asText());

        Cipher unwrap = Cipher.getInstance("AES/GCM/NoPadding");
        unwrap.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kekBytes, "AES"),
            new GCMParameterSpec(128, wrapIv));
        byte[] dek = unwrap.doFinal(wrappedDek);

        Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"),
            new GCMParameterSpec(128, dataIv));
        byte[] roundTripped = decrypt.doFinal(ciphertext);

        assertThat(new String(roundTripped, StandardCharsets.UTF_8)).isEqualTo(plaintext);
    }

    @Test
    void envModeRejectsMissingKekVar(@TempDir Path tmp) throws IOException {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        TenantArchiveEncryptionServiceImpl service = serviceWith(env, "env", "");

        Path plain = samplePlaintext(tmp, "anything");
        Path out = tmp.resolve("missing-kek.zip.enc");

        assertThatThrownBy(() -> service.encryptArchive(plain, out))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("HMS_TENANT_ARCHIVE_KEK");
    }

    @Test
    void envModeRejectsKekOfWrongLength(@TempDir Path tmp) throws IOException {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        // 16 bytes instead of 32 → wrong size for AES-256.
        String shortKek = Base64.getEncoder().encodeToString(new byte[16]);
        TenantArchiveEncryptionServiceImpl service = serviceWith(env, "env", shortKek);

        Path plain = samplePlaintext(tmp, "anything");
        Path out = tmp.resolve("short-kek.zip.enc");

        assertThatThrownBy(() -> service.encryptArchive(plain, out))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("32 bytes");
    }

    @Test
    void unsupportedKekSourceIsRejected(@TempDir Path tmp) throws IOException {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        TenantArchiveEncryptionServiceImpl service = serviceWith(env, "aws-kms", null);

        Path plain = samplePlaintext(tmp, "anything");
        Path out = tmp.resolve("unknown-source.zip.enc");

        assertThatThrownBy(() -> service.encryptArchive(plain, out))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Unsupported");
    }
}
