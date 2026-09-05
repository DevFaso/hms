package com.example.hms.service.platform;

import com.example.hms.security.EncryptedStringConverter;
import com.example.hms.security.EncryptionKeyHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * One-shot, idempotent encryption backfill for the platform credential
 * pointers (Tier 2 item 45).
 *
 * <p>WHY: adding {@code EncryptedStringConverter} to a column encrypts
 * FUTURE writes only — the converter deliberately returns legacy
 * non-{@code gcm1:} values verbatim, so credentials written before the
 * converter existed would stay plaintext at rest indefinitely unless
 * their row happened to be rewritten. This runner closes that gap: on
 * startup it finds every legacy value (no {@code gcm1:} prefix),
 * encrypts it with the configured key, and writes the ciphertext back.
 * Re-running is a no-op — the prefix check is the idempotency guard.
 *
 * <p>Skips quietly when no encryption key is configured (test/dev
 * profiles without {@code app.encryption.key}); logs loudly on any
 * failure but never blocks startup — a boot that fails on a backfill
 * would take the whole system down over rows that are no worse off than
 * they were yesterday.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CredentialReferenceEncryptionBackfill {

    private static final List<Map.Entry<String, String>> TARGETS = List.of(
        Map.entry("platform.organization_platform_services", "api_key_reference"),
        Map.entry("platform.hospital_platform_service_links", "credentials_reference"),
        Map.entry("platform.department_platform_service_links", "credentials_reference"));

    private final JdbcTemplate jdbcTemplate;

    private final EncryptedStringConverter converter = new EncryptedStringConverter();

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfill() {
        if (EncryptionKeyHolder.getKey() == null) {
            log.info("Credential-reference encryption backfill skipped: no encryption key configured");
            return;
        }
        for (Map.Entry<String, String> target : TARGETS) {
            try {
                int updated = backfillColumn(target.getKey(), target.getValue());
                if (updated > 0) {
                    log.info("Encrypted {} legacy {}.{} value(s) at rest",
                        updated, target.getKey(), target.getValue());
                }
            } catch (RuntimeException ex) {
                log.error("Credential-reference backfill failed for {}.{}: {}",
                    target.getKey(), target.getValue(), ex.getMessage(), ex);
            }
        }
    }

    private int backfillColumn(String table, String column) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT id, " + column + " AS val FROM " + table
                + " WHERE " + column + " IS NOT NULL AND " + column + " <> ''"
                + " AND " + column + " NOT LIKE 'gcm1:%'");
        int updated = 0;
        for (Map<String, Object> row : rows) {
            String cipherText = converter.convertToDatabaseColumn(String.valueOf(row.get("val")));
            updated += jdbcTemplate.update(
                "UPDATE " + table + " SET " + column + " = ? WHERE id = ?",
                cipherText, row.get("id"));
        }
        return updated;
    }
}
