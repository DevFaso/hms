package com.example.hms.architecture;

import com.example.hms.controller.PayerController;
import com.example.hms.model.PayerPlanRule;
import com.example.hms.repository.PayerPlanRepository;
import com.example.hms.repository.PayerPlanRuleRepository;
import com.example.hms.repository.PayerRepository;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing-money plan PR 6, guard for the class (plan section 3.1: tenancy and append-only history; owner decision
 * D-1: only the hospital admin and the accountant maintain payer rules).
 *
 * <ol>
 *   <li>Every payer-catalogue repository read names the hospital (or reads children of rows already read at their
 *       hospital), apart from the super-admin's global view, so no new finder reaches another hospital's payers.</li>
 *   <li>A coverage row's plan changes in ONE place, {@code PatientInsuranceServiceImpl.applyPayerPlan}, which first
 *       asks {@code PayerCatalogueService.requireLinkablePlan} for a plan of the row's hospital. No other main code
 *       calls {@code setPayerPlanId(}.</li>
 *   <li>A plan rule is never rewritten: every column but its end ({@code effective_to} and who ended it, when, why) is
 *       {@code updatable = false}; only {@code PayerCatalogueService} closes one; nothing deletes one.</li>
 *   <li>No route deletes anything of the catalogue, and no route edits a rule in place.</li>
 * </ol>
 */
@DisplayName("Payer catalogue: scoped reads, one link path, append-only rules")
class PayerCatalogueGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** Reads that do not name the hospital, and why each is safe. Shrink only. */
    private static final Set<String> UNSCOPED_READS = Set.of(
        // the verified super-admin's global view, read-only (PayerCatalogueService.requireReader)
        "PayerRepository#findAllByOrderByCodeAsc",
        // the plans of payers the caller has already read at their hospital (or in the global view)
        "PayerPlanRepository#findByPayerIdInOrderByCodeAsc",
        // the rules of plans already read the same way
        "PayerPlanRuleRepository#findByPlanIds",
        // the existence check of a code under a payer locked at the hospital first
        "PayerPlanRepository#existsByPayerIdAndCode");

    private static final Set<String> FROZEN_RULE_FIELDS = Set.of("hospitalId", "planId", "category",
        "priceListItemId", "coveragePercent", "excluded", "perActCeiling", "effectiveFrom", "createdBy");

    @Test
    void everyCatalogueReadNamesTheHospital() {
        List<String> unscoped = new ArrayList<>();
        for (Class<?> repository : List.of(PayerRepository.class, PayerPlanRepository.class,
                PayerPlanRuleRepository.class)) {
            for (Method method : repository.getDeclaredMethods()) {
                String key = repository.getSimpleName() + "#" + method.getName();
                if (!method.getName().matches("(find|exists|lock|count|stream)\\w*")) {
                    continue;
                }
                if (!method.getName().contains("HospitalId") && !method.getName().contains("AtHospital")
                    && !UNSCOPED_READS.contains(key)) {
                    unscoped.add(key);
                }
            }
        }
        assertThat(unscoped).as("a payer-catalogue read that reaches another hospital's rows (plan section 3.1)")
            .isEmpty();
    }

    @Test
    void aCoveragePlanIsSetOnlyThroughTheScopedLinkPath() throws IOException {
        Pattern setter = Pattern.compile("\\.setPayerPlanId\\(");
        Pattern method = Pattern.compile("\\n    (?:private|public|protected)[^;{=(]*?\\s(\\w+)\\s*\\(");
        List<String> callers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher call = setter.matcher(text);
                while (call.find()) {
                    String enclosing = "?";
                    Matcher header = method.matcher(text.substring(0, call.start()));
                    while (header.find()) {
                        enclosing = header.group(1);
                    }
                    callers.add(rel + "#" + enclosing);
                }
            }
        }
        assertThat(callers).as("only applyPayerPlan sets a coverage row's plan, after requireLinkablePlan")
            .containsOnly("service/PatientInsuranceServiceImpl.java#applyPayerPlan");
        String service = Files.readString(MAIN.resolve("service/PatientInsuranceServiceImpl.java"),
            StandardCharsets.UTF_8);
        int start = service.indexOf("private PlanLink applyPayerPlan(");
        String body = service.substring(start, service.indexOf("\n    }\n", start));
        assertThat(body.indexOf("payerCatalogueService.requireLinkablePlan(payerPlanId, hospitalId)"))
            .as("the plan is checked against the row's hospital before it is set")
            .isPositive()
            .isLessThan(body.indexOf("insurance.setPayerPlanId("));
    }

    @Test
    void aRuleIsWrittenOnceButItsEnd() throws NoSuchFieldException, IOException {
        for (String name : FROZEN_RULE_FIELDS) {
            Field field = PayerPlanRule.class.getDeclaredField(name);
            assertThat(field.getAnnotation(Column.class).updatable()).as(name + " must be updatable = false")
                .isFalse();
        }
        Pattern frozenSetter = Pattern.compile(
            "\\.set(PlanId|Category|PriceListItemId|CoveragePercent|Excluded|PerActCeiling)\\(");
        Pattern ruleType = Pattern.compile("\\bPayerPlanRule\\b");
        Pattern deleteCall = Pattern.compile("[rR]uleRepository\\.delete\\w*\\(");
        List<String> rewrites = new ArrayList<>();
        Set<String> closers = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (rel.equals("model/PayerPlanRule.java") || !ruleType.matcher(text).find()) {
                    continue;
                }
                String[] lines = text.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (frozenSetter.matcher(lines[i]).find() || deleteCall.matcher(lines[i]).find()) {
                        rewrites.add(rel + ":" + (i + 1) + " " + lines[i].trim());
                    }
                    if (lines[i].contains(".setEffectiveTo(") && lines[i].matches(".*\\b(replaced|previous|rule)\\.setEffectiveTo\\(.*")) {
                        closers.add(rel);
                    }
                }
            }
        }
        assertThat(rewrites).as("a plan rule is never rewritten or deleted").isEmpty();
        assertThat(closers).as("only PayerCatalogueService closes a rule")
            .containsExactly("service/billing/PayerCatalogueService.java");
    }

    @Test
    void noRouteDeletesAnythingOrEditsARule() {
        for (Method method : PayerController.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(DeleteMapping.class)).as(method.getName()).isNull();
            assertThat(method.getAnnotation(PatchMapping.class)).as(method.getName()).isNull();
            PutMapping put = method.getAnnotation(PutMapping.class);
            if (put != null) {
                for (String path : Stream.concat(Stream.of(put.value()), Stream.of(put.path())).toList()) {
                    assertThat(path).as(method.getName()).doesNotContain("rules");
                }
            }
        }
    }
}
