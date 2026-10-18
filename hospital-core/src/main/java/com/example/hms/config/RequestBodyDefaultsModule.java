package com.example.hms.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import org.springframework.stereotype.Component;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.Annotated;
import tools.jackson.databind.introspect.AnnotatedConstructor;
import tools.jackson.databind.introspect.NopAnnotationIntrospector;
import tools.jackson.databind.module.SimpleModule;

/**
 * Request bodies keep the defaults their classes declare when a form leaves
 * a field out or sends {@code null} for it (QA D23).
 *
 * <p>Two Jackson 3 changes (Spring Boot 4) broke that for the Lombok request
 * DTOs, which carry both {@code @NoArgsConstructor} and
 * {@code @AllArgsConstructor}:
 * <ol>
 *   <li>Jackson 3.1 deserialises through the all-arguments constructor even
 *       when a no-arguments one exists (databind#5318). A field the body
 *       leaves out then reaches that constructor as {@code null}: a primitive
 *       {@code boolean} fails with 400 "Cannot map `null` into type boolean"
 *       (patient registration, catalog, departments...), and every
 *       {@code @Builder.Default} - {@code active = true}, an empty list - is
 *       silently replaced by {@code false} or {@code null}.</li>
 *   <li>{@code FAIL_ON_NULL_FOR_PRIMITIVES} is on by default, so an explicit
 *       {@code "active": null} fails the same way.</li>
 * </ol>
 *
 * <p>The fixes, scoped to exactly those cases:
 * <ul>
 *   <li>A class that has a no-arguments constructor is built with it and its
 *       setters, as under Jackson 2, unless a constructor is explicitly marked
 *       {@code @JsonCreator}. Records and classes with no such constructor are
 *       untouched. The introspector is appended, so explicit annotations win.</li>
 *   <li>A {@code null} for a primitive {@code boolean} is skipped, so the field
 *       keeps its declared default exactly as when it is left out. Turning the
 *       feature off instead would coerce it to {@code false} and override a
 *       {@code true} default: a department or catalog entry created inactive
 *       because a box was left untouched. Numeric primitives keep the strict
 *       400: no numeric field here has a default a {@code null} could mean.</li>
 * </ul>
 *
 * <p>A {@code @Component} JacksonModule rather than a mapper customizer:
 * {@code @WebMvcTest} slices include JacksonModule components, so controller
 * tests deserialise exactly as production does. It has no dependencies.
 */
@Component
public class RequestBodyDefaultsModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public RequestBodyDefaultsModule() {
        super("RequestBodyDefaults");
    }

    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        context.configOverride(boolean.class)
            .setNullHandling(JsonSetter.Value.forValueNulls(Nulls.SKIP));
        context.appendAnnotationIntrospector(new PreferNoArgsConstructor());
    }

    /**
     * Disables the implicit (unannotated) constructors of a class that also
     * has a no-arguments constructor, so Jackson uses that one and the setters.
     */
    static final class PreferNoArgsConstructor extends NopAnnotationIntrospector {

        private static final long serialVersionUID = 1L;

        @Override
        public JsonCreator.Mode findCreatorAnnotation(MapperConfig<?> config, Annotated a) {
            if (!(a instanceof AnnotatedConstructor ctor) || ctor.getParameterCount() == 0) {
                return null;
            }
            Class<?> owner = ctor.getDeclaringClass();
            if (owner.isRecord() || !hasNoArgsConstructor(owner)) {
                return null;
            }
            return JsonCreator.Mode.DISABLED;
        }

        private static boolean hasNoArgsConstructor(Class<?> type) {
            try {
                type.getDeclaredConstructor();
                return true;
            } catch (NoSuchMethodException none) {
                return false;
            }
        }
    }
}
