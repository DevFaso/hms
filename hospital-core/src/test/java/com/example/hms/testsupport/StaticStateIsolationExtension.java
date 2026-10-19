package com.example.hms.testsupport;

import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Locale;

/**
 * Gives every plain unit-test class the same starting static state, whatever
 * ran before it in the same JVM: no {@link MessageUtil} message source and no
 * thread-bound locale. Registered for all classes through JUnit's extension
 * autodetection (META-INF/services + junit-platform.properties).
 *
 * <p>Several unit tests assert on {@code MessageUtil}'s no-source fallback
 * ({@code "[Missing translation] key - id"}) or on {@code Locale.getDefault()},
 * and only passed because the classes that leak a real source (any Spring
 * context, {@code AuthControllerTest}) or a French {@code LocaleContextHolder}
 * happened to run after them. Running the suite in parallel forks reshuffles
 * which classes share a JVM and exposed it (20 failures).
 *
 * <p>Spring test classes are left alone: a cached context sets the source
 * once, at creation, and a later class reusing that context relies on it.
 * The previous state is restored after each plain class, so a Spring class
 * that follows still sees the source its cached context installed.
 */
public class StaticStateIsolationExtension implements BeforeAllCallback, AfterAllCallback {

    private static final ExtensionContext.Namespace NS =
        ExtensionContext.Namespace.create(StaticStateIsolationExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        if (testClass.getEnclosingClass() != null && !java.lang.reflect.Modifier.isStatic(testClass.getModifiers())) {
            return; // @Nested: the enclosing class already set the state up
        }
        if (usesSpring(testClass)) {
            return;
        }
        ExtensionContext.Store store = context.getStore(NS);
        store.put("captured", Boolean.TRUE);
        store.put("source", ReflectionTestUtils.getField(MessageUtil.class, "messageSource"));
        store.put("locale", LocaleContextHolder.getLocaleContext() == null ? null : LocaleContextHolder.getLocale());
        MessageUtil.setMessageSource(null);
        LocaleContextHolder.resetLocaleContext();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(NS);
        if (store.get("captured") == null) {
            return;
        }
        MessageUtil.setMessageSource(store.get("source", MessageSource.class));
        Locale locale = store.get("locale", Locale.class);
        if (locale == null) {
            LocaleContextHolder.resetLocaleContext();
        } else {
            LocaleContextHolder.setLocale(locale);
        }
    }

    private static boolean usesSpring(Class<?> testClass) {
        return MergedAnnotations.from(testClass, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
            .stream(ExtendWith.class)
            .anyMatch(a -> Arrays.asList(a.getClassArray("value")).contains(SpringExtension.class));
    }
}
