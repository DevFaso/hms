package com.example.hms.security.audit;

import com.example.hms.controller.AuditEventLogController;
import com.example.hms.enums.AuditEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D701: {@code POST /api/audit-logs} took the actor, the hospital and the
 * event type from the request body, so a nurse wrote a LOGIN row as a hospital
 * admin and a patient a DATA_DELETE row as a doctor, and hospital A's audit
 * readers saw them as real. Audit rows are the server's to write, for the
 * authenticated caller.
 *
 * <p>This guard covers the whole class of that bug, not the one endpoint: no
 * controller handler, anywhere, may bind a request body that carries an
 * {@link AuditEventType} (that is an audit row the client composes), and the
 * audit log controller exposes no write mapping at all. Scans class metadata,
 * so a controller behind {@code @ConditionalOnProperty} or {@code @Profile} is
 * covered too.
 */
@DisplayName("No controller accepts a client-composed audit row (QA D701)")
class AuditWriteSurfaceGuardTest {

    @Test
    @DisplayName("the audit log controller has no POST/PUT/PATCH/DELETE handler")
    void auditLogControllerIsReadOnly() {
        List<String> writes = new ArrayList<>();
        for (Method m : AuditEventLogController.class.getDeclaredMethods()) {
            if (isWriteHandler(m)) {
                writes.add(m.getName());
            }
        }
        assertThat(writes).as("write handlers on AuditEventLogController").isEmpty();
    }

    @Test
    @DisplayName("no handler binds a request body that names an audit event type or an audit actor")
    void noControllerBindsAnAuditRowFromTheBody() throws Exception {
        List<Class<?>> controllers = restControllers();
        assertThat(controllers).as("controllers found by the scan").hasSizeGreaterThan(50);
        List<String> offenders = new ArrayList<>();
        for (Class<?> controller : controllers) {
            for (Method m : controller.getDeclaredMethods()) {
                for (Parameter p : m.getParameters()) {
                    if (p.isAnnotationPresent(RequestBody.class) && carriesAuditRow(p.getType())) {
                        offenders.add(controller.getSimpleName() + "." + m.getName() + "(" + p.getType().getSimpleName() + ")");
                    }
                }
            }
        }
        assertThat(offenders).as("handlers that let the client compose an audit row").isEmpty();
    }

    /** A body type with an AuditEventType field together with a UUID actor id is a client-composed audit row. */
    private static boolean carriesAuditRow(Class<?> type) {
        if (type.isPrimitive() || type.getName().startsWith("java.")) {
            return false;
        }
        boolean eventType = false;
        boolean actor = false;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == AuditEventType.class) {
                    eventType = true;
                }
                if (f.getType() == UUID.class && (f.getName().equals("userId") || f.getName().equals("actorId")
                        || f.getName().equals("assignmentId"))) {
                    actor = true;
                }
            }
        }
        return eventType && actor;
    }

    private static boolean isWriteHandler(Method m) {
        if (AnnotatedElementUtils.hasAnnotation(m, PostMapping.class)
                || AnnotatedElementUtils.hasAnnotation(m, PutMapping.class)
                || AnnotatedElementUtils.hasAnnotation(m, PatchMapping.class)
                || AnnotatedElementUtils.hasAnnotation(m, DeleteMapping.class)) {
            return true;
        }
        RequestMapping rm = m.getAnnotation(RequestMapping.class);
        return rm != null && (rm.method().length == 0 || Arrays.stream(rm.method()).anyMatch(x -> x != RequestMethod.GET));
    }

    private static List<Class<?>> restControllers() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        CachingMetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> found = new ArrayList<>();
        for (Resource resource : resolver.getResources("classpath*:com/example/hms/**/*.class")) {
            MetadataReader reader = factory.getMetadataReader(resource);
            if (reader.getAnnotationMetadata().hasAnnotation(RestController.class.getName())) {
                found.add(Class.forName(reader.getClassMetadata().getClassName()));
            }
        }
        return found;
    }
}
