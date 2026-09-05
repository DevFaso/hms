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
 * <p>Every SQL statement below is a full constant literal — three known
 * tables, no dynamic identifiers, values bound as parameters.
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

    private record Target(String label, String selectSql, String updateSql) {
    }

    private static final List<Target> TARGETS = List.of(
        new Target("organization_platform_services.api_key_reference",
            "SELECT id, api_key_reference AS val FROM platform.organization_platform_services"
                + " WHERE api_key_reference IS NOT NULL AND api_key_reference <> ''"
                + " AND api_key_reference NOT LIKE 'gcm1:%'",
            "UPDATE platform.organization_platform_services SET api_key_reference = ?"
                + " WHERE id = ?"),
        new Target("hospital_platform_service_links.credentials_reference",
            "SELECT id, credentials_reference AS val FROM platform.hospital_platform_service_links"
                + " WHERE credentials_reference IS NOT NULL AND credentials_reference <> ''"
                + " AND credentials_reference NOT LIKE 'gcm1:%'",
            "UPDATE platform.hospital_platform_service_links SET credentials_reference = ?"
                + " WHERE id = ?"),
        new Target("department_platform_service_links.credentials_reference",
            "SELECT id, credentials_reference AS val FROM platform.department_platform_service_links"
                + " WHERE credentials_reference IS NOT NULL AND credentials_reference <> ''"
                + " AND credentials_reference NOT LIKE 'gcm1:%'",
            "UPDATE platform.department_platform_service_links SET credentials_reference = ?"
                + " WHERE id = ?"));

    private final JdbcTemplate jdbcTemplate;

    private final EncryptedStringConverter converter = new EncryptedStringConverter();

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfill() {
        if (EncryptionKeyHolder.getKey() == null) {
            log.info("Credential-reference encryption backfill skipped: no encryption key configured");
            return;
        }
        for (Target target : TARGETS) {
            try {
                int updated = backfillTarget(target);
                if (updated > 0) {
                    log.info("Encrypted {} legacy {} value(s) at rest", updated, target.label());
                }
            } catch (RuntimeException ex) {
                log.error("Credential-reference backfill failed for {}: {}",
                    target.label(), ex.getMessage(), ex);
            }
        }
    }

    private int backfillTarget(Target target) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(target.selectSql());
        int updated = 0;
        for (Map<String, Object> row : rows) {
            String cipherText = converter.convertToDatabaseColumn(String.valueOf(row.get("val")));
            updated += jdbcTemplate.update(target.updateSql(), cipherText, row.get("id"));
        }
        return updated;
    }
}
