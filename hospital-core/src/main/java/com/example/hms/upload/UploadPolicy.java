package com.example.hms.upload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names what a multipart endpoint accepts. {@link UploadAdmissionInterceptor}
 * checks every file of the request against it before the handler runs, and
 * refuses any file sent to a handler that carries no policy.
 * {@code UploadPolicyCoverageGuardTest} fails the build when a multipart
 * handler is added without one.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UploadPolicy {
    UploadKind value();
}
