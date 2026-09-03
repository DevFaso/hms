package com.example.hms.config;

import com.example.hms.security.audit.PatientAccessAuditInterceptor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link PatientAccessAuditInterceptor}.
 *
 * <p>Its own configuration class rather than a method on {@code WebConfig},
 * which is about static resource serving — and because a
 * {@code HandlerInterceptor} declared as a {@code @Component} is not
 * registered by Spring on its own. {@code ActingContextInterceptor} in this
 * codebase is exactly that: a {@code @Component} implementing
 * {@code HandlerInterceptor} that nothing ever adds to a registry, so it has
 * never run. (Its logic is duplicated by the live
 * {@code ActingContextArgumentResolver}, so nothing is broken by that — but it
 * is why this class exists and why the wiring is tested.)
 *
 * <p><b>Why the interceptor arrives through an {@link ObjectProvider}.</b>
 * {@code @WebMvcTest} slices scan {@code WebMvcConfigurer} beans but not
 * {@code @Component}s, so this class loads into every controller slice while
 * the interceptor it wants does not exist there. Injecting it directly failed
 * 257 tests across 105 slices with
 * {@code NoSuchBeanDefinitionException: PatientAccessAuditInterceptor} — the
 * same shape of breakage {@code ReadOnlyModeFilter} caused, for the same
 * reason. Resolving it lazily lets a slice construct this config and register
 * nothing, which is what a slice wants anyway.
 *
 * <p>The cost of that flexibility is that a missing interceptor becomes a
 * no-op instead of a startup failure, and a silently unregistered audit hook
 * is the precise failure this whole change exists to end. Hence the warning
 * below: in any context that serves real traffic the bean is present, and if
 * it ever is not, the log says so at startup rather than the disclosure page
 * quietly going empty.
 */
@Slf4j
@Configuration
public class PatientAccessAuditConfig implements WebMvcConfigurer {

    private final ObjectProvider<PatientAccessAuditInterceptor> interceptorProvider;

    public PatientAccessAuditConfig(
            ObjectProvider<PatientAccessAuditInterceptor> interceptorProvider) {
        this.interceptorProvider = interceptorProvider;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        PatientAccessAuditInterceptor interceptor = interceptorProvider.getIfAvailable();
        if (interceptor == null) {
            log.warn("[PATIENT-ACCESS] No PatientAccessAuditInterceptor bean — patient record "
                + "reads will NOT be recorded, and the patient disclosure page will show only "
                + "break-glass, sharing and export events. Expected in @WebMvcTest slices; "
                + "a defect anywhere else.");
            return;
        }
        // No path pattern: the interceptor decides what is a patient read by
        // looking at the resolved handler, which is more reliable than a URL
        // prefix. Patient data hangs off /encounters, /lab-results, /admissions
        // and a dozen other roots, so a prefix list would be the same
        // remember-to-add-it problem this replaces.
        registry.addInterceptor(interceptor);
    }
}
