package com.example.hms.i18n;

import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.payload.dto.BreakGlassDeclareRequestDTO;
import com.example.hms.payload.dto.BreakGlassReviewRequestDTO;
import com.example.hms.payload.dto.BreakGlassRevokeRequestDTO;
import com.example.hms.payload.dto.EncounterNoteAddendumRequestDTO;
import com.example.hms.payload.dto.clinical.labor.DeliveryRecordRequestDTO;
import com.example.hms.payload.dto.encounter.workspace.EncounterWorkspaceAddendumRequestDTO;
import com.example.hms.payload.dto.mfa.MfaVerifyRequest;
import com.example.hms.payload.dto.mortality.DeathDisputeOutcomeDTO;
import com.example.hms.payload.dto.mortality.DeathDisputeRequestDTO;
import com.example.hms.payload.dto.mortality.DeathRecordAmendmentDTO;
import com.example.hms.payload.dto.mortality.DeathRecordEnteredInErrorDTO;
import com.example.hms.payload.dto.mortality.DeathRecordRequestDTO;
import com.example.hms.payload.dto.nurse.NursingNoteAddendumRequestDTO;
import com.example.hms.payload.dto.transfer.TransferCancellationRequestDTO;
import com.example.hms.payload.dto.transfer.TransferCompletionRequestDTO;
import com.example.hms.payload.dto.transfer.TransferOrderRequestDTO;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3861 (r77, r81), D4026 and the D3852 class: a request constraint with no
 * message of its own answered the validator's sentence (« motif : ne doit pas
 * être vide », « code : la taille doit être comprise entre 6 et 8 »).
 * <ul>
 *   <li>Every request constraint (payload DTOs, controller request records)
 *       either names a {@code message = "{key}"} or is of a kind the shared
 *       mapping words in the house style ({@link GlobalExceptionHandler#HOUSE_WORDED_CONSTRAINTS},
 *       the QA D4158 mapping, kept complete by {@code ValidationWordingGuardTest}).</li>
 *   <li>The clinical refusals QA reported carry the app's own words for
 *       each field, and those words resolve in French, English and Spanish.</li>
 * </ul>
 */
@DisplayName("D3861: request constraints answer in the app's words")
class RequestConstraintMessageGuardTest {

    private static final List<Path> REQUEST_SOURCES = List.of(
        Paths.get("src/main/java/com/example/hms/payload"),
        Paths.get("src/main/java/com/example/hms/controller"));

    private static final List<String> JAKARTA_CONSTRAINTS = List.of(
        "AssertFalse", "AssertTrue", "DecimalMax", "DecimalMin", "Digits", "Email", "Future", "FutureOrPresent",
        "Max", "Min", "Negative", "NegativeOrZero", "NotBlank", "NotEmpty", "NotNull", "Null", "Past",
        "PastOrPresent", "Pattern", "Positive", "PositiveOrZero", "Size");

    private static final Pattern CONSTRAINT = Pattern.compile(
        "@(?:jakarta\\.validation\\.constraints\\.)?(" + String.join("|", JAKARTA_CONSTRAINTS) + ")\\b");

    /** The request bodies QA reported (D3861, D4026) and their siblings on the same screens. */
    private static final List<Class<?>> REPORTED = List.of(
        BreakGlassDeclareRequestDTO.class, BreakGlassReviewRequestDTO.class, BreakGlassRevokeRequestDTO.class,
        EncounterNoteAddendumRequestDTO.class, EncounterWorkspaceAddendumRequestDTO.class,
        DeliveryRecordRequestDTO.class, DeathRecordRequestDTO.class, DeathDisputeRequestDTO.class,
        DeathDisputeOutcomeDTO.class, DeathRecordEnteredInErrorDTO.class, DeathRecordAmendmentDTO.class,
        NursingNoteAddendumRequestDTO.class, TransferCancellationRequestDTO.class,
        TransferCompletionRequestDTO.class, TransferOrderRequestDTO.class, MfaVerifyRequest.class,
        com.example.hms.controller.MfaController.MfaLoginVerifyRequest.class);

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("a request constraint without a message is of a kind the house mapping words")
    void everyBareRequestConstraintIsHouseWorded() throws IOException {
        Map<String, List<String>> unworded = new TreeMap<>();
        int bare = 0;
        for (Path file : javaFiles()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            boolean[] literal = literals(source);
            Matcher m = CONSTRAINT.matcher(source);
            while (m.find()) {
                if (literal[m.start()] || hasMessage(source, m.end())) {
                    continue;
                }
                bare++;
                if (!GlobalExceptionHandler.HOUSE_WORDED_CONSTRAINTS.contains(m.group(1))) {
                    unworded.computeIfAbsent(m.group(1), k -> new ArrayList<>())
                        .add(file.getFileName() + ":" + lineOf(source, m.start()));
                }
            }
        }
        assertThat(bare).as("the scan found no bare request constraint at all").isGreaterThan(500);
        assertThat(unworded)
            .as("request constraints with no message = \"{key}\" whose kind the house mapping does not word:"
                + " the reader would get the validator's sentence%s", SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the reported clinical and MFA refusals carry the app's own message on every constraint")
    void theReportedBodiesCarryTheirOwnMessages() {
        List<String> bare = new ArrayList<>();
        int seen = 0;
        for (Class<?> type : REPORTED) {
            for (Map.Entry<String, Annotation> c : constraints(type)) {
                seen++;
                String template = message(c.getValue());
                if (template == null || !template.startsWith("{") || template.startsWith("{jakarta.")
                        || template.startsWith("{org.hibernate.")) {
                    bare.add(type.getSimpleName() + "." + c.getKey() + " @"
                        + c.getValue().annotationType().getSimpleName());
                }
            }
        }
        assertThat(seen).as("constraints found on the reported bodies").isGreaterThan(60);
        assertThat(bare).as("constraints of the reported bodies still on the validator's wording%s",
            SourceScan.MANDATE).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fr", "en", "es"})
    @DisplayName("the reported refusals read the app's words in each language")
    void theReportedRefusalsReadTheAppsWords(String language) {
        Locale locale = Locale.forLanguageTag(language);
        MessageSource bundles = TestMessageSources.bundles();
        try (LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean()) {
            validator.setValidationMessageSource(bundles);
            validator.afterPropertiesSet();
            LocaleContextHolder.setLocale(locale);

            BreakGlassDeclareRequestDTO breakGlass = new BreakGlassDeclareRequestDTO();
            breakGlass.setReason("court");
            // #1378 (QA r81 D4108): the break-glass reason follows the shared clinical-override minimum.
            assertThat(only(validator, breakGlass, "reason"))
                .isEqualTo(bundles.getMessage("clinical.override.reasonMin", null, locale));

            assertThat(only(validator, new MfaVerifyRequest("123"), "code"))
                .isEqualTo(bundles.getMessage("mfa.enrollment.codeLength", null, locale));

            DeathRecordRequestDTO death = new DeathRecordRequestDTO();
            assertThat(only(validator, death, "diedAt"))
                .isEqualTo(bundles.getMessage("mortality.validation.diedAtRequired", null, locale));
            assertThat(only(validator, death, "immediateCause"))
                .isEqualTo(bundles.getMessage("mortality.validation.immediateCauseRequired", null, locale));

            DeliveryRecordRequestDTO delivery = new DeliveryRecordRequestDTO();
            assertThat(only(validator, delivery, "deliveryMode"))
                .isEqualTo(bundles.getMessage("delivery.validation.modeRequired", null, locale));

            NursingNoteAddendumRequestDTO addendum = new NursingNoteAddendumRequestDTO();
            assertThat(only(validator, addendum, "attestNoAbbreviations"))
                .isEqualTo(bundles.getMessage("addendum.validation.attestNoAbbreviationsRequired", null, locale));

            TransferOrderRequestDTO transfer = new TransferOrderRequestDTO();
            assertThat(only(validator, transfer, "reason"))
                .isEqualTo(bundles.getMessage("transfer.r7.reasonRequired", null, locale));
        }
    }

    private static String only(LocalValidatorFactoryBean validator, Object body, String property) {
        var violations = validator.validateProperty(body, property);
        assertThat(violations).as(property).isNotEmpty();
        String message = violations.iterator().next().getMessage();
        assertThat(message).as(property).doesNotContain("{", "ne doit pas", "la taille", "must not", "size must");
        return message;
    }

    /** "property" -> constraint, for the fields (and record components) of a body. */
    private static List<Map.Entry<String, Annotation>> constraints(Class<?> type) {
        List<Map.Entry<String, Annotation>> out = new ArrayList<>();
        List<java.lang.reflect.AnnotatedElement> elements = new ArrayList<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                elements.add(component.getAccessor());
                try {
                    elements.add(type.getDeclaredField(component.getName()));
                } catch (NoSuchFieldException ignored) {
                    // a record component always has its field
                }
            }
        } else {
            elements.addAll(List.of(type.getDeclaredFields()));
        }
        for (var element : elements) {
            String name = element instanceof Field f ? f.getName() : ((Method) element).getName();
            for (Annotation a : element.getDeclaredAnnotations()) {
                if (a.annotationType().getPackageName().equals("jakarta.validation.constraints")) {
                    out.add(Map.entry(name, a));
                }
            }
        }
        return out;
    }

    private static String message(Annotation annotation) {
        try {
            return (String) annotation.annotationType().getMethod("message").invoke(annotation);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** Whether the annotation at {@code end} has a {@code message =} argument. */
    private static boolean hasMessage(String source, int end) {
        int i = end;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        if (i >= source.length() || source.charAt(i) != '(') {
            return false;
        }
        String arguments = source.substring(i, SourceScan.closingParen(source, i) + 1);
        return Pattern.compile("\\bmessage\\s*=").matcher(arguments).find();
    }

    /** Which characters of {@code source} lie in a string or char literal (a Javadoc is stripped first). */
    private static boolean[] literals(String source) {
        boolean[] inside = new boolean[source.length() + 1];
        for (int i = 0; i < source.length(); i++) {
            int end = SourceScan.endOfLiteralOrComment(source, i);
            if (end >= 0) {
                for (int j = i; j <= end && j < inside.length; j++) {
                    inside[j] = true;
                }
                i = end;
            }
        }
        return inside;
    }

    private static List<Path> javaFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path root : REQUEST_SOURCES) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
            }
        }
        return out;
    }

    private static int lineOf(String source, int index) {
        return 1 + (int) source.substring(0, index).chars().filter(ch -> ch == '\n').count();
    }
}
