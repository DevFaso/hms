package com.example.hms.service.tenant;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Wrap a packaged tenant archive in an at-rest encryption envelope
 * (MVP-c batch — MVP-2c).
 *
 * <p>The contract is intentionally narrow: take a plaintext file, write
 * an encrypted file. Key management lives behind a {@code KEK} source
 * abstraction so the production deployment can swap in a real KMS
 * (AWS KMS / GCP KMS / HashiCorp Vault) without changing this service.
 *
 * <p>Today's bundled implementation uses AES-256-GCM with a per-archive
 * data-encryption key (DEK) wrapped by a key-encryption-key (KEK)
 * resolved via {@code hms.tenant-archive.kek-source}:
 * <ul>
 *   <li>{@code env} — KEK is base64-decoded from
 *       {@code HMS_TENANT_ARCHIVE_KEK} env var (32 bytes).
 *   <li>{@code noop} — passthrough (no encryption); permitted only
 *       when the active Spring profile contains {@code dev} / {@code test}.
 *   <li>(future) {@code aws-kms}, {@code gcp-kms}, {@code vault} — drop-in
 *       impls of {@code KekResolver} when ops wires the real KMS.
 * </ul>
 *
 * <p>Each archive carries a sidecar manifest
 * ({@code <archive>.envelope.json}) with the cipher params + the
 * encrypted DEK so a future decryption path can reconstruct the key
 * by querying the same KEK source.
 */
public interface TenantArchiveEncryptionService {

    /**
     * Encrypt a packaged ZIP into the encrypted output file. Writes a
     * sidecar envelope manifest at {@code outputPath + ".envelope.json"}.
     *
     * @return EncryptionResult describing what was written.
     */
    EncryptionResult encryptArchive(Path plaintextZip, Path outputPath) throws IOException;

    /**
     * Outcome record. {@code mode = NOOP} means encryption was
     * disabled in this environment — the output is a copy of the
     * plaintext (the envelope manifest still records the choice).
     */
    record EncryptionResult(Path outputPath, Path envelopePath, Mode mode, String cipher) {
        public enum Mode { ENCRYPTED, NOOP }
    }
}
