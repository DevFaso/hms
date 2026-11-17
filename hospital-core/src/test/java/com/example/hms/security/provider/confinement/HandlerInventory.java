package com.example.hms.security.provider.confinement;

import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every MVC handler of the application as "METHOD pattern" lines, built from
 * the same {@link RequestMappingInfo} Spring MVC builds at startup (class and
 * method mappings combined). Classes are read by a metadata scan, not a
 * component scan: a component scan evaluates {@code @ConditionalOnProperty}
 * and would silently drop the controllers that are off by default.
 *
 * <p>A handler that declares no HTTP method answers every method; it is
 * listed once per method a client can send it.
 */
final class HandlerInventory {

    static final List<String> ALL_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /** One handler: the HTTP method, the mapping pattern and where it is declared. */
    record Handler(String method, String pattern, String declaredAt) {
        String key() {
            return method + " " + pattern;
        }
    }

    private HandlerInventory() {
    }

    static List<Handler> handlers() {
        InfoReader reader = new InfoReader();
        List<Handler> found = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                RequestMappingInfo info = reader.read(method, controller);
                if (info == null || info.getPathPatternsCondition() == null) {
                    continue;
                }
                Set<RequestMethod> declared = info.getMethodsCondition().getMethods();
                List<String> methods = declared.isEmpty()
                    ? ALL_METHODS
                    : declared.stream().map(RequestMethod::name).toList();
                for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                    for (String httpMethod : methods) {
                        found.add(new Handler(httpMethod, pattern,
                            controller.getSimpleName() + "." + method.getName()));
                    }
                }
            }
        }
        return found;
    }

    static TreeSet<String> keys(List<Handler> handlers) {
        TreeSet<String> keys = new TreeSet<>();
        handlers.forEach(handler -> keys.add(handler.key()));
        return keys;
    }

    private static List<Class<?>> controllers() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        CachingMetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> found = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources("classpath*:com/example/hms/**/*.class")) {
                MetadataReader reader = factory.getMetadataReader(resource);
                String className = reader.getClassMetadata().getClassName();
                if (className.endsWith("Test") || className.endsWith("IT") || className.contains("$")
                    || !resource.getURL().toString().contains("/main/")) {
                    continue;
                }
                if (reader.getAnnotationMetadata().hasMetaAnnotation(Controller.class.getName())
                    || reader.getAnnotationMetadata().hasAnnotation(Controller.class.getName())) {
                    found.add(Class.forName(className));
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        return found;
    }

    /** Spring MVC's own mapping reader, without a running application. */
    private static final class InfoReader extends RequestMappingHandlerMapping {
        InfoReader() {
            StaticApplicationContext context = new StaticApplicationContext();
            context.refresh();
            setApplicationContext(context);
            afterPropertiesSet();
        }

        RequestMappingInfo read(Method method, Class<?> handlerType) {
            return getMappingForMethod(method, handlerType);
        }
    }
}
