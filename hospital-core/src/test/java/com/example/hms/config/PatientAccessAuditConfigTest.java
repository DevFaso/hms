package com.example.hms.config;

import com.example.hms.security.audit.PatientAccessAuditInterceptor;
import com.example.hms.security.audit.WriteAuditInterceptor;
import com.example.hms.service.AuditEventLogService;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PatientAccessAuditConfig")
class PatientAccessAuditConfigTest {

    private PatientAccessAuditInterceptor interceptor() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditEventLogService> auditProvider = mock(ObjectProvider.class);
        return new PatientAccessAuditInterceptor(auditProvider, 30, 1000);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<PatientAccessAuditInterceptor> providerOf(
            PatientAccessAuditInterceptor value) {
        ObjectProvider<PatientAccessAuditInterceptor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<WriteAuditInterceptor> writeProviderOf(WriteAuditInterceptor interceptor) {
        ObjectProvider<WriteAuditInterceptor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(interceptor);
        return provider;
    }

    private WriteAuditInterceptor writeInterceptor() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditEventLogService> auditProvider = mock(ObjectProvider.class);
        return new WriteAuditInterceptor(auditProvider);
    }

    private List<?> registeredInterceptors(InterceptorRegistry registry) {
        // getInterceptors() is protected on InterceptorRegistry; reaching it
        // reflectively is what lets this assert the wiring rather than a
        // reimplementation of it.
        return (List<?>) ReflectionTestUtils.invokeMethod(registry, "getInterceptors");
    }

    @Test
    @DisplayName("actually registers the interceptor")
    void registersTheInterceptor() {
        // A HandlerInterceptor declared as a @Component is not registered by
        // Spring on its own, and nothing about the resulting silence looks
        // wrong: the bean exists, the class compiles, the tests of its logic
        // pass, and it never runs. ActingContextInterceptor in this codebase
        // has been in exactly that state since it was written.
        //
        // For an audit hook that failure is invisible in the worst way — the
        // disclosure page keeps rendering, just with nothing on it, which is
        // the precise bug this interceptor exists to fix.
        PatientAccessAuditInterceptor interceptor = interceptor();
        InterceptorRegistry registry = new InterceptorRegistry();

        new PatientAccessAuditConfig(providerOf(interceptor), writeProviderOf(null)).addInterceptors(registry);

        assertThat(registeredInterceptors(registry))
            .asInstanceOf(list(Object.class))
            .contains(interceptor);
    }

    @Test
    @DisplayName("registers it for every path, not a prefix list")
    void registersWithoutAPathAllowlist() {
        // Patient data hangs off /encounters, /lab-results, /admissions,
        // /maternity and a dozen other roots. A prefix list would recreate the
        // remember-to-add-it problem the interceptor replaces, one root at a
        // time, and the omission would again be invisible.
        InterceptorRegistry registry = new InterceptorRegistry();
        new PatientAccessAuditConfig(providerOf(interceptor()), writeProviderOf(null)).addInterceptors(registry);

        List<?> registrations = (List<?>) ReflectionTestUtils.getField(registry, "registrations");
        assertThat(registrations).hasSize(1);

        assertThat(ReflectionTestUtils.getField(registrations.get(0), "includePatterns"))
            .as("no include patterns — the handler decides, not the URL")
            .satisfiesAnyOf(
                patterns -> assertThat(patterns).isNull(),
                patterns -> assertThat((List<?>) patterns).isEmpty());
    }

    @Test
    @DisplayName("registers nothing, quietly, when the interceptor bean is absent")
    void toleratesAnAbsentInterceptor() {
        // @WebMvcTest slices scan WebMvcConfigurers but not @Components, so
        // this config loads into all 105 controller slices without the
        // interceptor beside it. Injecting it directly failed 257 tests with
        // NoSuchBeanDefinitionException — this is the tolerance that fixed
        // them, and it needs to keep working or the slices break again.
        InterceptorRegistry registry = new InterceptorRegistry();
        PatientAccessAuditConfig config = new PatientAccessAuditConfig(providerOf(null), writeProviderOf(null));

        assertThatCode(() -> config.addInterceptors(registry)).doesNotThrowAnyException();
        assertThat(registeredInterceptors(registry)).asInstanceOf(list(Object.class)).isEmpty();
    }

    @Test
    @DisplayName("registers the write-audit interceptor beside the read one, also without a path list")
    void registersTheWriteInterceptorToo() {
        WriteAuditInterceptor write = writeInterceptor();
        InterceptorRegistry registry = new InterceptorRegistry();
        new PatientAccessAuditConfig(providerOf(interceptor()), writeProviderOf(write)).addInterceptors(registry);
        assertThat(registeredInterceptors(registry)).asInstanceOf(list(Object.class)).hasSize(2).contains(write);
        List<?> registrations = (List<?>) ReflectionTestUtils.getField(registry, "registrations");
        assertThat(ReflectionTestUtils.getField(registrations.get(1), "includePatterns"))
            .satisfiesAnyOf(
                patterns -> assertThat(patterns).isNull(),
                patterns -> assertThat((List<?>) patterns).isEmpty());
    }

    @Test
    @DisplayName("the write interceptor is registered even when the read one is absent")
    void writeInterceptorDoesNotDependOnTheReadOne() {
        WriteAuditInterceptor write = writeInterceptor();
        InterceptorRegistry registry = new InterceptorRegistry();
        new PatientAccessAuditConfig(providerOf(null), writeProviderOf(write)).addInterceptors(registry);
        assertThat(registeredInterceptors(registry)).asInstanceOf(list(Object.class)).containsExactly(write);
    }
}
