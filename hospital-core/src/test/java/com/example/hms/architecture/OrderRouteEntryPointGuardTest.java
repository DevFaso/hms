package com.example.hms.architecture;

import com.example.hms.cdshooks.rules.CdsRuleContext;
import com.example.hms.cdshooks.rules.CdsRuleEngine;
import com.example.hms.cdshooks.rules.OrderedRoute;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-01 (class: a dose rule entry point that does not carry the ordered route, so an oral limit judges
 * an IV order). Fails when an entry point is added without the route: a new overload of the engine, a
 * second way to build {@link CdsRuleEngine.ProposedOrder}, a caller that passes a literal null or the
 * catalogue item's route in its place. The rules themselves are held to reading the route too.
 */
@DisplayName("CR-01: every rule entry point carries the ordered route")
class OrderRouteEntryPointGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern ENTRY = Pattern.compile(
        "\\b(?:evaluateProposedPrescription|ruleEngine\\.buildContext|ProposedOrder)\\(");
    private static final int ROUTE_ARGUMENT_OF_PROPOSED_ORDER = 7;
    private static final int STRINGS_WITH_THE_ROUTE = 4;
    private static final int BPA_ARGUMENTS = 2;

    @Test
    void theEngineHasNoOverloadWithoutTheRoute() {
        for (Method method : CdsRuleEngine.class.getDeclaredMethods()) {
            if (!Set.of("evaluateProposedPrescription", "buildContext").contains(method.getName())
                    || method.getParameterCount() == 1) {
                continue;
            }
            List<Class<?>> types = Arrays.asList(method.getParameterTypes());
            long strings = types.stream().filter(String.class::equals).count();
            assertThat(types.contains(CdsRuleEngine.ProposedOrder.class) || strings == STRINGS_WITH_THE_ROUTE)
                .as("%s takes the order, or the name, code, dose and route", method).isTrue();
        }
    }

    @Test
    void theOrderAndTheContextHoldTheRouteAndTheOrderHasOneConstructor() {
        assertThat(CdsRuleEngine.ProposedOrder.class.getDeclaredConstructors()).hasSize(1);
        assertThat(Arrays.stream(CdsRuleEngine.ProposedOrder.class.getRecordComponents()).map(RecordComponent::getName))
            .contains("route", "formulation");
        assertThat(Arrays.stream(CdsRuleContext.class.getRecordComponents()))
            .anySatisfy(component -> {
                assertThat(component.getName()).isEqualTo("proposedRoute");
                assertThat(component.getType()).isEqualTo(OrderedRoute.class);
            });
    }

    @Test
    void everyCallerPassesTheOrdersOwnRoute() throws IOException {
        Set<String> callers = new TreeSet<>();
        List<String> missing = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                if (relative.equals("cdshooks/rules/CdsRuleEngine.java")) {
                    continue;
                }
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher entry = ENTRY.matcher(code);
                while (entry.find()) {
                    callers.add(relative);
                    List<String> arguments = arguments(code, entry.end());
                    // A BPA context has no order; an order built in the call is read at its own match.
                    if (arguments.size() <= BPA_ARGUMENTS || arguments.get(arguments.size() - 1).contains("ProposedOrder(")) {
                        continue;
                    }
                    String route = entry.group().startsWith("ProposedOrder")
                        ? arguments.get(ROUTE_ARGUMENT_OF_PROPOSED_ORDER)
                        : arguments.get(arguments.size() - 1);
                    if (!route.toLowerCase(java.util.Locale.ROOT).contains("route") || route.equals("null")
                            || route.toLowerCase(java.util.Locale.ROOT).contains("item")) {
                        missing.add(relative + ": " + route);
                    }
                }
            }
        }
        assertThat(missing).isEmpty();
        // Grows with a new entry point on purpose: it is read, and passes the check above.
        assertThat(callers).containsExactlyInAnyOrder(
            "cdshooks/service/BpaProtocolsCdsService.java",
            "cdshooks/service/MedicationAllergyCdsService.java",
            "cdshooks/service/MedicationPrescribeRulesCdsService.java",
            "cdshooks/service/OrderSelectRulesCdsService.java",
            "cdshooks/service/OrderSignRulesCdsService.java",
            "service/PrescriptionServiceImpl.java",
            "service/impl/NurseTaskServiceImpl.java");
    }

    @Test
    void theDoseRulesReadTheRoute() throws IOException {
        for (String rule : List.of("AdultParacetamolRule", "PediatricDoseRule")) {
            assertThat(Files.readString(MAIN.resolve("cdshooks/rules/" + rule + ".java"), StandardCharsets.UTF_8))
                .as(rule).contains("context.proposedRoute()");
        }
    }

    /** The call's top-level arguments, from just after its opening parenthesis. */
    private static List<String> arguments(String code, int from) {
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int start = from;
        boolean inText = false;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"' && code.charAt(i - 1) != '\\') {
                inText = !inText;
            } else if (!inText) {
                if (c == '(' || c == '{') {
                    depth++;
                } else if (c == ')' || c == '}') {
                    if (depth == 0) {
                        arguments.add(code.substring(start, i).trim());
                        return arguments;
                    }
                    depth--;
                } else if (c == ',' && depth == 0) {
                    arguments.add(code.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        return arguments;
    }
}
