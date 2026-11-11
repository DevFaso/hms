package com.example.hms.testsupport;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.mock.web.MockServletContext;
import org.springframework.objenesis.SpringObjenesis;
import org.springframework.stereotype.Controller;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring's real mapping registry over every controller of the application,
 * without booting anything or calling a constructor: {@link RequestMappingHandlerMapping}
 * reads the controllers as it does at startup. A metadata scan, because a component scan drops the
 * controllers behind {@code @ConditionalOnProperty} (see SchedulerLockCoverageTest).
 */
public final class ControllerMappingRegistry {

    private static final RequestMappingHandlerMapping MAPPING = build();

    private ControllerMappingRegistry() {
    }

    public static RequestMappingHandlerMapping mapping() {
        return MAPPING;
    }

    private static RequestMappingHandlerMapping build() {
        GenericWebApplicationContext context = new GenericWebApplicationContext(new MockServletContext());
        context.refresh();
        // Empty instances: a lookup resolves the handler bean, and nothing here calls one.
        SpringObjenesis objenesis = new SpringObjenesis();
        for (Class<?> controller : controllers()) {
            context.getBeanFactory().registerSingleton(controller.getName(), objenesis.newInstance(controller));
        }
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();
        return mapping;
    }

    private static List<Class<?>> controllers() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        CachingMetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> found = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources("classpath*:com/example/hms/**/*.class")) {
                // The application's own controllers: a test declares its own, on the same paths.
                if (resource.getURL().getPath().contains("/test/")) {
                    continue;
                }
                MetadataReader reader = factory.getMetadataReader(resource);
                if (reader.getAnnotationMetadata().hasMetaAnnotation(Controller.class.getName())
                        || reader.getAnnotationMetadata().hasAnnotation(Controller.class.getName())) {
                    Class<?> type = Class.forName(reader.getClassMetadata().getClassName());
                    if (AnnotatedElementUtils.hasAnnotation(type, Controller.class) && !type.isInterface()) {
                        found.add(type);
                    }
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        return found;
    }
}
