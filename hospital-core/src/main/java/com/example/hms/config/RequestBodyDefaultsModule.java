package com.example.hms.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.example.hms.exception.RequestBodyProblems;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamContext;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.MapperBuilder;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.Annotated;
import tools.jackson.databind.introspect.AnnotatedConstructor;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.introspect.NopAnnotationIntrospector;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.databind.util.ClassUtil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

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
        context.addHandler(new CollectMalformedRequestValues());
        if (context.getOwner() instanceof MapperBuilder<?, ?> builder) {
            blankTypedValuesReadAsAbsent(builder);
        }
    }

    /**
     * QA D1316 (round 29): a blank string in a typed field (a select left on
     * its placeholder, a cleared date or time picker) failed the parse, and
     * the parser stops at its first failure, so a body with several blank
     * typed fields named only one of them. A blank enum, UUID, date, time or
     * number is now read as absent: bean validation then names every
     * required one in a single answer, and an optional one is simply not
     * given. An unparsable non-blank value is still refused by name.
     */
    static void blankTypedValuesReadAsAbsent(MapperBuilder<?, ?> builder) {
        for (LogicalType type : List.of(LogicalType.Enum, LogicalType.OtherScalar, LogicalType.DateTime,
                LogicalType.Integer, LogicalType.Float)) {
            builder.withCoercionConfig(type, config -> {
                config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull);
                config.setAcceptBlankAsEmpty(Boolean.TRUE);
            });
        }
    }

    /**
     * QA D1349 (round 30): Jackson stopped at the first value it could not
     * convert, so a body with two malformed values (vitals with "abc" and
     * "xyz", an imaging order with modality and priority both "ZZZ"), or a
     * malformed value beside blank required fields, named only the first one.
     *
     * <p>While a request body is read ({@link RequestBodyProblems#collecting()}),
     * each value that cannot be converted - a string no enum, UUID, date, time,
     * number or flag accepts, a fraction for a whole number, an object or a
     * list where a single value belongs - is recorded by its JSON path and read
     * as absent, and the reading goes on. {@code MalformedRequestBodyAdvice}
     * then refuses the request naming every recorded value together with the
     * body's bean-validation refusals: a recorded body never reaches a
     * controller. Outside a request body read every hook answers
     * {@code NOT_HANDLED}, so Jackson fails exactly as before. A body of the
     * wrong shape as a whole (no property to name) keeps the strict failure.
     */
    static final class CollectMalformedRequestValues extends DeserializationProblemHandler {

        @Override
        public Object handleWeirdStringValue(DeserializationContext ctxt, Class<?> targetType,
                                             String valueToConvert, String failureMsg) {
            // QA D1364 (round 31): a blank yes/no (a cleared toggle) is absent, as a blank
            // enum or date is (D1316), so a required one is named "required" with the rest.
            // A primitive keeps the malformed answer: absent would read as false there.
            if (targetType == Boolean.class && valueToConvert != null && valueToConvert.isBlank()
                    && RequestBodyProblems.collecting()) {
                return null;
            }
            return collect(ctxt.getParser(), false, targetType);
        }

        /**
         * QA D1672: a property a {@code @RejectsUnknownFields} request does not
         * declare is recorded, so the body is refused naming it instead of the
         * change being dropped. Any other class keeps Jackson's default.
         */
        @Override
        public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
                                             tools.jackson.databind.ValueDeserializer<?> deserializer,
                                             Object beanOrClass, String propertyName) {
            Class<?> type = beanOrClass instanceof Class<?> c ? c : beanOrClass == null ? null : beanOrClass.getClass();
            if (type == null || !type.isAnnotationPresent(com.example.hms.validation.RejectsUnknownFields.class)
                    || !RequestBodyProblems.collecting()) {
                return false;
            }
            // On a '{' or '[' the parser already stands in the value's own context.
            JsonToken token = p.currentToken();
            boolean structure = token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY;
            TokenStreamContext context = p.streamReadContext();
            String path = jsonPath(structure && context != null ? context.getParent() : context);
            if (path.isEmpty()) {
                path = propertyName;
            }
            if (!RequestBodyProblems.recordNotAccepted(path)) {
                return false;
            }
            p.skipChildren();
            return true;
        }

        @Override
        public Object handleWeirdNumberValue(DeserializationContext ctxt, Class<?> targetType,
                                             Number valueToConvert, String failureMsg) {
            return collect(ctxt.getParser(), false, targetType);
        }

        @Override
        public Object handleUnexpectedToken(DeserializationContext ctxt, JavaType targetType, JsonToken t,
                                            JsonParser p, String failureMsg) {
            boolean structure = t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY;
            if (t == JsonToken.START_OBJECT && isListBodyRoot(p, targetType)) {
                // QA D1364 (round 31): one object sent where the body is a list of them is
                // read as that one-element list, so its refusals name the element's fields
                // ("[0].channel") instead of "the request could not be read".
                List<Object> one = new java.util.ArrayList<>();
                one.add(ctxt.readValue(p, targetType.getContentType()));
                return one;
            }
            Object absent = collect(p, structure, targetType.getRawClass());
            if (absent != NOT_HANDLED && structure) {
                p.skipChildren();
            }
            return absent;
        }

        private static Object collect(JsonParser p, boolean structure, Class<?> targetType) {
            if (p == null || !RequestBodyProblems.collecting()) {
                return NOT_HANDLED;
            }
            TokenStreamContext context = p.streamReadContext();
            // On a '{' or '[' the parser already stands in the value's own context.
            String path = jsonPath(structure && context != null ? context.getParent() : context);
            if (path.isEmpty() || !RequestBodyProblems.recordMalformed(path)) {
                return NOT_HANDLED;
            }
            return targetType.isPrimitive() ? ClassUtil.defaultValue(targetType) : null;
        }

        /** The body itself (not a field of it) is a list, and a request body is being read. */
        private static boolean isListBodyRoot(JsonParser p, JavaType targetType) {
            if (p == null || !RequestBodyProblems.collecting() || !targetType.isCollectionLikeType()
                    || !targetType.getRawClass().isAssignableFrom(java.util.ArrayList.class)) {
                return false;
            }
            TokenStreamContext context = p.streamReadContext();
            TokenStreamContext parent = context == null ? null : context.getParent();
            return parent != null && parent.inRoot();
        }

        /** "items[0].dose" style; empty at the root of the body. */
        static String jsonPath(TokenStreamContext context) {
            Deque<String> segments = new ArrayDeque<>();
            for (TokenStreamContext c = context; c != null && !c.inRoot(); c = c.getParent()) {
                if (c.inArray()) {
                    segments.push("[" + c.getCurrentIndex() + "]");
                } else if (c.currentName() != null) {
                    segments.push(c.currentName());
                }
            }
            StringBuilder path = new StringBuilder();
            for (String segment : segments) {
                if (!path.isEmpty() && !segment.startsWith("[")) {
                    path.append('.');
                }
                path.append(segment);
            }
            return path.toString();
        }
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
