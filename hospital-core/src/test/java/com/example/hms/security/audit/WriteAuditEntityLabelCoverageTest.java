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

    /**
     * QA D909: the super-admin audit log read "Recent Activity Bundle" in
     * French and Spanish. Write rows are not the only ones with an entity
     * type: services name theirs explicitly - on the audit builder, through a
     * cross-tenant read or a {@code logAudit}/{@code audit} helper - and those
     * had no such guard. Every literal (or same-file constant) found there must
     * be keyed in every locale, in the UPPER_SNAKE form the portal reads.
     */
    @Test
    void everyExplicitAuditEntityTypeIsKeyedInEveryLocale() throws Exception {
        Map<String, String> emitted = explicitEntityTypes();
        assertThat(emitted).as("explicit entity types found").hasSizeGreaterThan(20)
            .containsKey("RECENT_ACTIVITY_BUNDLE");
        JsonMapper mapper = JsonMapper.builder().build();
        List<String> missing = new ArrayList<>();
        for (String locale : LOCALES) {
            JsonNode group = mapper.readTree(I18N.resolve(locale + ".json").toFile())
                .path("PORTAL").path("ENUM").path("AUDIT_ENTITY_TYPE");
            emitted.forEach((type, source) -> {
                if (group.path(type).isMissingNode()) {
                    missing.add(locale + ": " + type + " (" + source + ")");
                }
            });
        }
        assertThat(missing).as("PORTAL.ENUM.AUDIT_ENTITY_TYPE keys the audit log needs").isEmpty();
    }

    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final java.util.regex.Pattern CONSTANT = java.util.regex.Pattern.compile(
        "static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"([^\"]*)\"\\s*;");
    private static final java.util.regex.Pattern IDENTIFIER = java.util.regex.Pattern.compile("[A-Za-z_]\\w*");
    private static final java.util.regex.Pattern ENTITY_CODE = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    /** entity type (UPPER_SNAKE) -> the file that names it. */
    private static Map<String, String> explicitEntityTypes() throws Exception {
        Map<String, String> out = new TreeMap<>();
        List<Path> sources;
        try (var walk = java.nio.file.Files.walk(MAIN_JAVA)) {
            sources = walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
        for (Path file : sources) {
            String src = java.nio.file.Files.readString(file);
            Map<String, String> constants = new java.util.HashMap<>();
            java.util.regex.Matcher constant = CONSTANT.matcher(src);
            while (constant.find()) {
                constants.putIfAbsent(constant.group(1), constant.group(2));
            }
            List<String> named = new ArrayList<>();
            named.addAll(arguments(src, "entityType", 1, 0, ""));
            named.addAll(arguments(src, "recordCrossTenantRead", 3, 0, ""));
            // PharmacyServiceSupport.logAudit(event, description, resourceId, entityType).
            named.addAll(arguments(src, "logAudit", 4, 3, ""));
            // SensitivityTaggingServiceImpl.audit(event, entityType, resourceId, ...).
            named.addAll(arguments(src, "audit", 0, 1, "AuditEventType."));
            for (String argument : named) {
                String value = literalOrConstant(argument, constants);
                if (value != null && ENTITY_CODE.matcher(value).matches()) {
                    String code = value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
                    out.putIfAbsent(code, MAIN_JAVA.relativize(file).toString());
                }
            }
        }
        return out;
    }

    /** A string literal's content, or a same-file constant's value, else null. */
    private static String literalOrConstant(String argument, Map<String, String> constants) {
        if (argument.length() > 1 && argument.startsWith("\"") && argument.endsWith("\"")) {
            return argument.substring(1, argument.length() - 1);
        }
        return IDENTIFIER.matcher(argument).matches() ? constants.get(argument) : null;
    }

    /**
     * The argument at {@code index} of every call to {@code method} (a whole
     * name, so {@code audit} is not {@code logAudit}) that has {@code arity}
     * top-level arguments (0: any) and whose first argument starts with
     * {@code firstPrefix}.
     */
    private static List<String> arguments(String src, String method, int arity, int index, String firstPrefix) {
        List<String> found = new ArrayList<>();
        String call = method + "(";
        int from = 0;
        while ((from = src.indexOf(call, from)) >= 0) {
            int at = from;
            from += call.length();
            if (at > 0 && Character.isJavaIdentifierPart(src.charAt(at - 1))) {
                continue;
            }
            List<String> args = splitArguments(src, from);
            boolean shaped = args.size() > index && (arity == 0 || args.size() == arity)
                && args.get(0).trim().startsWith(firstPrefix);
            if (shaped) {
                found.add(args.get(index).trim());
            }
        }
        return found;
    }

    /** The top-level, comma-separated arguments from {@code start} to the matching ')'. */
    private static List<String> splitArguments(String src, int start) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        StringBuilder current = new StringBuilder();
        for (int i = start; i < src.length(); i++) {
            char c = src.charAt(i);
            current.append(c);
            if (quote != 0) {
                if (c == '\\' && i + 1 < src.length()) {
                    current.append(src.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                if (depth == 0) {
                    current.setLength(current.length() - 1);
                    args.add(current.toString());
                    return args;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                current.setLength(current.length() - 1);
                args.add(current.toString());
                current.setLength(0);
            }
        }
        return List.of();
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
