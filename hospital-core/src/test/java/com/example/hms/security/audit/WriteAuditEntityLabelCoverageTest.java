package com.example.hms.security.audit;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D528: the audit log's "Entity" column rendered the entity type a write
 * row carries through {@code | enumLabel: 'auditEntityType'}, and a type with
 * no {@code PORTAL.ENUM.AUDIT_ENTITY_TYPE} key fell back to Title-Cased English
 * on a French screen ("Lab Order"). The type is derived from the route
 * ({@link WriteAuditInterceptor#entityTypeFor}), so every write route of every
 * controller is resolved here exactly as the interceptor does, and each type
 * must be keyed in all three portal locales.
 */
class WriteAuditEntityLabelCoverageTest {

    private static final Path I18N = Path.of("..", "hospital-portal", "src", "assets", "i18n");
    private static final List<String> LOCALES = List.of("en", "fr", "es");

    @Test
    void everyWriteAuditEntityTypeIsKeyedInEveryLocale() throws Exception {
        Map<String, String> emitted = emittedEntityTypes();
        assertThat(emitted).as("write routes found").hasSizeGreaterThan(20);
        JsonMapper mapper = JsonMapper.builder().build();
        List<String> missing = new ArrayList<>();
        for (String locale : LOCALES) {
            JsonNode group = mapper.readTree(I18N.resolve(locale + ".json").toFile())
                .path("PORTAL").path("ENUM").path("AUDIT_ENTITY_TYPE");
            emitted.forEach((type, route) -> {
                if (group.path(type).isMissingNode()) {
                    missing.add(locale + ": " + type + " (" + route + ")");
                }
            });
        }
        assertThat(missing).as("PORTAL.ENUM.AUDIT_ENTITY_TYPE keys the audit log needs").isEmpty();
    }

    /** entity type -> one route that emits it. */
    private static Map<String, String> emittedEntityTypes() throws Exception {
        Map<String, String> out = new TreeMap<>();
        for (Class<?> controller : restControllers()) {
            List<String> bases = paths(AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));
            for (Method method : controller.getDeclaredMethods()) {
                String verb = writeVerb(method);
                if (verb == null) {
                    continue;
                }
                WriteAudited annotation = AnnotatedElementUtils.findMergedAnnotation(method, WriteAudited.class);
                if (annotation == null) {
                    annotation = AnnotatedElementUtils.findMergedAnnotation(controller, WriteAudited.class);
                }
                if (annotation != null && annotation.skip()) {
                    continue;
                }
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                for (String base : bases) {
                    for (String tail : paths(mapping)) {
                        String pattern = (base + "/" + tail).replaceAll("/+", "/");
                        if (WriteAuditInterceptor.isReadOnlyPost(verb, pattern)) {
                            continue;
                        }
                        out.putIfAbsent(WriteAuditInterceptor.entityTypeFor(pattern, annotation),
                            verb + " " + pattern);
                    }
                }
            }
        }
        return out;
    }

    private static String writeVerb(Method method) {
        if (AnnotatedElementUtils.hasAnnotation(method, PostMapping.class)) {
            return "POST";
        }
        if (AnnotatedElementUtils.hasAnnotation(method, PutMapping.class)) {
            return "PUT";
        }
        if (AnnotatedElementUtils.hasAnnotation(method, PatchMapping.class)) {
            return "PATCH";
        }
        if (AnnotatedElementUtils.hasAnnotation(method, DeleteMapping.class)) {
            return "DELETE";
        }
        return null;
    }

    private static List<String> paths(RequestMapping mapping) {
        if (mapping == null) {
            return List.of("");
        }
        Set<String> all = new TreeSet<>(List.of(mapping.path()));
        all.addAll(List.of(mapping.value()));
        return all.isEmpty() ? List.of("") : List.copyOf(all);
    }

    /** Read from class metadata, so a @Profile or @ConditionalOnProperty controller is not dropped. */
    private static List<Class<?>> restControllers() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> out = new ArrayList<>();
        for (Resource resource : resolver.getResources("classpath*:com/example/hms/**/*.class")) {
            MetadataReader reader = readers.getMetadataReader(resource);
            if (reader.getAnnotationMetadata().hasAnnotation(RestController.class.getName())) {
                out.add(Class.forName(reader.getClassMetadata().getClassName()));
            }
        }
        return out;
    }
}
