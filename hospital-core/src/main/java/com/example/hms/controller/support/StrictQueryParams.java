package com.example.hms.controller.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A query parameter the handler does not declare is refused with a
 * translated 400 instead of being ignored (QA D798, D801).
 *
 * <p>On a search endpoint, an ignored filter is worse than an error: the
 * caller asks for one event type and reads every row as if it were the
 * answer. {@code /super-admin/audit-search?eventType=...} did that until
 * round 12, and {@code /audit-logs?eventTypes=...} did it again in the
 * round-12 re-test. {@link StrictQueryParamsInterceptor} reads the accepted
 * names off the handler method itself, so the list can never drift from
 * the code.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface StrictQueryParams {
}
