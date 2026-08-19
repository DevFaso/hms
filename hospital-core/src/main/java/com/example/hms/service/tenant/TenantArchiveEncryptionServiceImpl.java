package com.example.hms.service.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AES-256-GCM envelope encryption for tenant archives (MVP-c batch — MVP-2c).
 *
 * <p>Per-archive data-encryption-key is generated fresh
 * (256-bit AES) and wrapped by a key-encryption-key resolved at startup
 * from {@code hms.tenant-archive.kek-source}. Wrapping is also AES-GCM
 * — the wrap IV + ciphertext are stored on the envelope manifest, not
 * the archive itself, so a partner can encrypt-once / wrap-many if a
 * KEK rotation lands.
 */
@Service
@Slf4j
public class TenantArchiveEncryptionServiceImpl implements TenantArchiveEncryptionService {

    private static final String AES = "AES";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final int DEK_BYTES = 32;
    private static final int CHUNK = 8192;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RNG = new SecureRandom();

    private final Environment springEnvironment;

    @Value("${hms.tenant-archive.kek-source:noop}")
    private String kekSource;

    @Value("${HMS_TENANT_ARCHIVE_KEK:}")
    private String envKekBase64;

    public TenantArchiveEncryptionServiceImpl(Environment springEnvironment) {
        this.springEnvironment = springEnvironment;
    }

    /**
     * Fail-fast at startup if the KEK configuration is not safe for the
     * active profile. Today the only unsafe combination is
     * {@code kek-source=noop} outside dev / test — silent plaintext
     * archives in production are a Tier-1 data-protection violation and
     * must abort boot before any tenant data flows. The runtime check in
     * {@link #passthroughOrReject} stays as a defensive backstop for
     * non-Spring-managed instantiation (tests).
     */
    @PostConstruct
    void validateConfiguration() {
        if ("noop".equalsIgnoreCase(kekSource) && !isDevOrTestProfile()) {
            String activeProfiles = String.join(",", springEnvironment.getActiveProfiles());
            String message = "Refusing to start: hms.tenant-archive.kek-source=noop is only "
                + "permitted in dev/test profiles (active profiles=[" + activeProfiles + "]). "
                + "Set hms.tenant-archive.kek-source=env and export HMS_TENANT_ARCHIVE_KEK "
                + "(base64-encoded 32 bytes) before deploying.";
            log.error("[TENANT-ARCHIVE-ENCRYPTION] {}", message);
            throw new IllegalStateException(message);
        }
        log.info("[TENANT-ARCHIVE-ENCRYPTION] kek-source={} validated for profiles=[{}]",
            kekSource, String.join(",", springEnvironment.getActiveProfiles()));
    }

    @Override
    public EncryptionResult encryptArchive(Path plaintextZip, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());

        if ("noop".equalsIgnoreCase(kekSource)) {
            return passthroughOrReject(plaintextZip, outputPath);
        }

        byte[] kek = resolveKek();
        try {
            return encryptWithKek(plaintextZip, outputPath, kek);
        } catch (GeneralSecurityException ex) {
            // Wrap to IOException so the caller's IOException catch covers
            // both the file IO and the cipher path uniformly.
            throw new IOException("Tenant archive encryption failed: " + ex.getMessage(), ex);
        } finally {
            // Best-effort wipe so the KEK doesn't linger on the heap.
            java.util.Arrays.fill(kek, (byte) 0);
        }
    }

    private EncryptionResult passthroughOrReject(Path plaintextZip, Path outputPath) throws IOException {
        if (!isDevOrTestProfile()) {
            throw new IOException(
                "hms.tenant-archive.kek-source=noop is only permitted in dev/test profiles. "
                    + "Set kek-source=env (with HMS_TENANT_ARCHIVE_KEK) for non-dev environments.");
        }
        log.warn("[TENANT-ARCHIVE-ENCRYPTION] kek-source=noop — copying plaintext archive without encryption (dev only).");
        Files.copy(plaintextZip, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path envelopePath = writeNoopEnvelope(outputPath);
        return new EncryptionResult(outputPath.toAbsolutePath(),
            envelopePath, EncryptionResult.Mode.NOOP, "none");
    }

    private boolean isDevOrTestProfile() {
        String[] profiles = springEnvironment.getActiveProfiles();
        if (profiles.length == 0) {
            // Default profile is treated as dev — same posture as
            // OrganizationLifecycleServiceImpl's MFA gate.
            return true;
        }
        for (String p : profiles) {
            // Substring-match on the dev/test/local family so variants
            // like `local-h2`, `dev-uat`, `unit-test` all light up the
            // same gate without having to be enumerated. Production
            // profiles (`prod`, `staging`) intentionally don't contain
            // any of these tokens and so fall through to the strict
            // KEK-required path. The seeders elsewhere in the project
            // (RoleSeeder, OrganizationSecuritySeeder,
            // DevSyntheticDataSeeder, HospitalOrganizationAlignmentRunner)
            // treat `local` and `local-h2` as dev-equivalent — keeping
            // this gate consistent with that convention.
            if (p.contains("dev")
                || p.contains("test")
                || p.contains("local")
                || "default".equals(p)) {
                return true;
            }
        }
        return false;
    }

    private byte[] resolveKek() throws IOException {
        if ("env".equalsIgnoreCase(kekSource)) {
            if (envKekBase64 == null || envKekBase64.isBlank()) {
                throw new IOException("hms.tenant-archive.kek-source=env requires HMS_TENANT_ARCHIVE_KEK to be set.");
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(envKekBase64.trim());
            } catch (IllegalArgumentException ex) {
                throw new IOException("HMS_TENANT_ARCHIVE_KEK is not valid base64: " + ex.getMessage(), ex);
            }
            if (decoded.length != DEK_BYTES) {
                throw new IOException("HMS_TENANT_ARCHIVE_KEK must decode to 32 bytes (got " + decoded.length + ").");
            }
            return decoded;
        }
        throw new IOException("Unsupported hms.tenant-archive.kek-source=" + kekSource
            + " (expected env or noop; aws-kms/gcp-kms/vault are reserved for follow-up).");
    }

    private EncryptionResult encryptWithKek(Path plaintextZip, Path outputPath, byte[] kek)
        throws IOException, GeneralSecurityException {

        // 1. Generate a fresh DEK (AES-256).
        KeyGenerator kg = KeyGenerator.getInstance(AES);
        kg.init(DEK_BYTES * 8);
        SecretKey dek = kg.generateKey();

        // 2. Encrypt the archive with DEK + a random IV.
        byte[] dataIv = randomIv();
        Cipher dataCipher = Cipher.getInstance(AES_GCM);
        dataCipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_BITS, dataIv));

        try (InputStream in = Files.newInputStream(plaintextZip);
             OutputStream out = Files.newOutputStream(outputPath);
             javax.crypto.CipherOutputStream cipherOut = new javax.crypto.CipherOutputStream(out, dataCipher)) {
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = in.read(buf)) != -1) {
                cipherOut.write(buf, 0, n);
            }
        }

        // 3. Wrap the DEK with the KEK using a separate IV.
        byte[] wrapIv = randomIv();
        Cipher wrapCipher = Cipher.getInstance(AES_GCM);
        wrapCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, AES),
            new GCMParameterSpec(GCM_TAG_BITS, wrapIv));
        byte[] wrappedDek = wrapCipher.doFinal(dek.getEncoded());

        // 4. Sidecar envelope manifest (kept alongside the encrypted archive).
        //    Pass the KEK material so kek_id is derived from the actual key
        //    and stays stable across JVM restarts (Copilot review fix —
        //    the prior JVM-startup-salt scheme made historical envelopes
        //    operationally unmappable after every redeploy).
        String kekId = deriveKekId(kek);
        Path envelopePath = writeEncryptedEnvelope(outputPath, kekId, wrappedDek, wrapIv, dataIv);
        log.info("[TENANT-ARCHIVE-ENCRYPTION] Encrypted {} -> {} (envelope at {}, kek_id={})",
            plaintextZip, outputPath, envelopePath, kekId);

        return new EncryptionResult(outputPath.toAbsolutePath(),
            envelopePath, EncryptionResult.Mode.ENCRYPTED, AES_GCM);
    }

    private Path writeEncryptedEnvelope(Path outputPath, String kekId,
                                        byte[] wrappedDek, byte[] wrapIv, byte[] dataIv)
        throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("envelope_version", 1);
        envelope.put("created_at", Instant.now().toString());
        envelope.put("kek_source", kekSource);
        envelope.put("kek_id", kekId);
        envelope.put("cipher", AES_GCM);
        envelope.put("data_iv_b64", Base64.getEncoder().encodeToString(dataIv));
        envelope.put("wrap_iv_b64", Base64.getEncoder().encodeToString(wrapIv));
        envelope.put("wrapped_dek_b64", Base64.getEncoder().encodeToString(wrappedDek));
        return writeEnvelope(outputPath, envelope);
    }

    private Path writeNoopEnvelope(Path outputPath) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("envelope_version", 1);
        envelope.put("created_at", Instant.now().toString());
        envelope.put("kek_source", "noop");
        envelope.put("cipher", "none");
        envelope.put("note", "Plaintext copy — dev/test profiles only.");
        return writeEnvelope(outputPath, envelope);
    }

    private Path writeEnvelope(Path archivePath, Map<String, Object> envelope) throws IOException {
        Path envelopePath = archivePath.resolveSibling(archivePath.getFileName() + ".envelope.json");
        Files.write(envelopePath,
            MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope));
        return envelopePath;
    }

    private byte[] randomIv() {
        byte[] iv = new byte[IV_BYTES];
        RNG.nextBytes(iv);
        return iv;
    }

    /**
     * Stable KEK identifier derived from a hash of the KEK material
     * itself (Copilot review fix — the prior JVM-startup-salt scheme
     * regenerated kek_id on every deploy, leaving historical envelopes
     * operationally unmappable to the key that wrapped them).
     *
     * <p>Format: {@code <kekSource>:<first-16-hex-chars-of-SHA256(kek)>}.
     * SHA-256 truncation to 64 bits is collision-safe for the small set
     * of KEKs in practice and does not leak the key material (a
     * preimage attack on truncated SHA-256 is infeasible). Survives JVM
     * restart, changes on key rotation — both desirable.
     */
    private String deriveKekId(byte[] kek) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(kek);
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return kekSource + ":" + hex;
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by every JRE; the catch is bookkeeping
            // to keep the method signature checked-exception-free.
            throw new IllegalStateException("SHA-256 unavailable; JVM is broken", ex);
        }
    }
}
