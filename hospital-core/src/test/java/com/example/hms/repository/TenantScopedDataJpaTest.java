package com.example.hms.repository;

import com.example.hms.security.EncryptionKeyHolder;
import com.example.hms.security.tenant.TenantContextAccessor;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The {@code @DataJpaTest} slice for repositories behind the tenant filter:
 * the {@code test} profile plus the two beans the entity layer needs outside a
 * full context ({@link TenantContextAccessor} for {@code TenantAwareJpaRepository},
 * {@link EncryptionKeyHolder} for the PHI column converters).
 *
 * <p>One place for the next bean the slice needs. It also keeps every class
 * that uses it on the same slice configuration, so they share one cached
 * context: one more EntityManagerFactory in the test JVM is enough to exhaust
 * the capped heap under the full suite. A class that needs more
 * ({@code @EnableJpaRepositories} config, say) adds its own {@code @Import}
 * next to this one, and gets a context of its own.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@DataJpaTest
@ActiveProfiles("test")
@Import({TenantContextAccessor.class, EncryptionKeyHolder.class})
public @interface TenantScopedDataJpaTest {
}
