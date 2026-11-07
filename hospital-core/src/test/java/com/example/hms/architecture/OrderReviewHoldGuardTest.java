package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orders held for review (user ruling, round 36): every write endpoint of the controllers that act on an existing
 * order is classified. It asks {@code OrderReviewHoldGuard} in the named service method, or it says why it does not
 * use the order. A new endpoint there fails until somebody decides. A new controller acting on an order joins
 * {@link #ACTING_ON_ORDERS}: {@link #everyWriterOfAnOrderIsInScope} fails until it does.
 */
@DisplayName("A held order is not used, and every entry point on an order is classified")
class OrderReviewHoldGuardTest {

    private static final Path CONTROLLERS = Path.of("src/main/java/com/example/hms/controller");
    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");

    private static final Set<String> ACTING_ON_ORDERS = Set.of(
        "Prescription", "Dispense", "NurseTask", "Transfusion", "StockOutRouting", "RefillApproval", "OrderReviewHold");

    private static final Pattern WRITE_MAPPING = Pattern.compile("@(?:Post|Put|Patch|Delete)Mapping\\b");
    private static final Pattern ENDPOINT = Pattern.compile("\\n\\s*+public\\s[^;{=(]*+\\(");
    private static final Pattern ANY_METHOD = Pattern.compile("\\n    (?:public|private|protected)\\s[^;{=(]*+\\(");
    private static final String GUARD_CALL = "OrderReviewHoldGuard.refuse";

    /** {@code Controller#endpoint | ServiceClass#method} holding the call. */
    private static final String GATED = """
        Prescription#sign | PrescriptionServiceImpl#signPrescription
        Prescription#cosign | PrescriptionServiceImpl#cosignPrescription
        Prescription#pharmacistVerify | PharmacistVerificationService#verify
        Prescription#dispatchSms | PrescriptionSmsDispatchServiceImpl#dispatch
        Dispense#dispense | DispenseServiceImpl#validateFill
        Dispense#markReady | DispenseServiceImpl#validateFill
        Dispense#handOver | DispenseServiceImpl#handOver
        NurseTask#verifyMedication | NurseTaskServiceImpl#requireOrderStillStands
        NurseTask#administerMedication | NurseTaskServiceImpl#requireOrderStillStands
        Transfusion#issueUnit | TransfusionServiceImpl#issueUnit
        Transfusion#emergencyRelease | TransfusionServiceImpl#emergencyRelease
        Transfusion#startAdministration | TransfusionServiceImpl#startAdministration
        StockOutRouting#routeToPartner | StockOutRoutingServiceImpl#validateRoutableStatus
        StockOutRouting#printForPatient | StockOutRoutingServiceImpl#validateRoutableStatus
        StockOutRouting#backOrder | StockOutRoutingServiceImpl#validateRoutableStatus
        RefillApproval#approve | RefillApprovalServiceImpl#decide
        """;

    /** {@code Controller#endpoint,endpoint | why it does not use a held order}. */
    private static final String NOT_A_USE = """
        Prescription#create | a new order written after the dispute follows the normal workflow
        Prescription#pediatricDoseReview,requestClarification,resolveClarification | a review note: nothing leaves, signing and dispensing are refused
        Prescription#update,delete | an edit moves the version, so an older reconfirmation is refused; a withdrawal stops the order
        Dispense#claimQueueRow,takeOverQueueRow,releaseQueueRow | the work queue: nothing leaves the pharmacy
        Dispense#cancelReady,cancelDispense | hands stock back
        NurseTask#createHandoff,completeHandoff,captureVitals,createNursingTask,completeNursingTask,markInboxRead,createCareNote | no order
        Transfusion#createRequest | a new order written after the dispute follows the normal workflow
        Transfusion#recordBloodGroup,recordCrossmatch | a laboratory result, which must always be written; issue and start are refused
        Transfusion#receiveUnit,discardUnit | blood bank stock
        Transfusion#cancelRequest | stops the request
        Transfusion#completeAdministration,stopAdministration,recordReaction | treatment already running: never held, stop and complete stay available
        StockOutRouting#partnerRespond,confirmPartnerDispense,partnerNoShow | a partner's outcome of a routing made before the hold
        RefillApproval#reject,pause | refuses a refill
        OrderReviewHold#continueOrder,replaceOrder,stopOrder,recordAssessment | the review itself
        """;

    @Test
    @DisplayName("every write endpoint on an order is gated, or says why it is not a use")
    void everyEndpointIsClassified() throws IOException {
        Set<String> classified = new TreeSet<>(gated().keySet());
        classified.addAll(notAUse());
        Set<String> endpoints = endpoints();

        assertThat(endpoints).as("the scan finds the writes").hasSizeGreaterThan(40);
        assertThat(endpoints.stream().filter(e -> !classified.contains(e)).toList())
            .as("an entry point on an order nobody decided about: does it use a held order? Call OrderReviewHoldGuard "
                + "and add it to GATED, or add it to NOT_A_USE with why" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(classified.stream().filter(e -> !endpoints.contains(e)).toList())
            .as("classified, but no such write endpoint any more: delete its line").isEmpty();
    }

    @Test
    @DisplayName("a gated endpoint's service method calls the guard")
    void everyGatedEndpointCallsTheGuard() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> row : gated().entrySet()) {
            String[] target = row.getValue().split("#");
            Path file = services().get(target[0]);
            assertThat(file).as(target[0]).isNotNull();
            String body = bodyOf(SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8)), target[1]);
            if (body == null || !body.contains(GUARD_CALL)) {
                missing.add(row.getKey() + " -> " + row.getValue());
            }
        }
        assertThat(missing).as("uses a held order").isEmpty();
    }

    /** A service that moves an order on, or a bag out, sits behind a classified controller. */
    @Test
    @DisplayName("nothing outside these services moves an order's fill, dose or bag on")
    void everyWriterOfAnOrderIsInScope() throws IOException {
        List<String> others = new ArrayList<>();
        Set<String> known = Set.of("PrescriptionServiceImpl", "PharmacistVerificationService", "PrescriptionSmsDispatchServiceImpl",
            "DispenseServiceImpl", "NurseTaskServiceImpl", "TransfusionServiceImpl", "StockOutRoutingServiceImpl",
            "RefillApprovalServiceImpl", "PrescriptionWithdrawal", "PartnerExchangeService", "PartnerResponseTimeoutScheduler",
            "AdmissionStayEncounterService", "MllpInboundAdtVisitProjectionServiceImpl", "FiveRightsVerificationService",
            "OrderSetItemDispatcher", "PartnerSmsWebhookService");
        Pattern moves = Pattern.compile("setStatus\\(\\s*+(?:\\w++\\.)*+(?:BloodUnitStatus\\.(?:ISSUED|TRANSFUSED)|DispenseStatus\\.COMPLETED"
            + "|PrescriptionStatus\\.(?:SIGNED|DISPENSED)|MedicationAdministrationStatus\\.GIVEN)");
        for (Map.Entry<String, Path> service : services().entrySet()) {
            String source = SourceScan.stripComments(Files.readString(service.getValue(), StandardCharsets.UTF_8));
            if (!known.contains(service.getKey()) && moves.matcher(source).find()) {
                others.add(service.getKey());
            }
        }
        assertThat(others).as("moves an order on from outside the classified services: classify it").isEmpty();
    }

    private static Map<String, String> gated() {
        Map<String, String> rows = new TreeMap<>();
        GATED.lines().map(String::strip).filter(line -> !line.isEmpty()).forEach(line -> {
            String[] cells = line.split("\\s*+\\|\\s*+", 2);
            rows.put(cells[0], cells[1]);
        });
        return rows;
    }

    private static Set<String> notAUse() {
        Set<String> rows = new TreeSet<>();
        NOT_A_USE.lines().map(String::strip).filter(line -> !line.isEmpty()).forEach(line -> {
            String[] head = line.split("\\s*+\\|\\s*+", 2)[0].split("#", 2);
            for (String endpoint : head[1].split(",")) {
                rows.add(head[0] + "#" + endpoint);
            }
        });
        return rows;
    }

    private static Set<String> endpoints() throws IOException {
        Set<String> endpoints = new TreeSet<>();
        try (Stream<Path> files = Files.walk(CONTROLLERS)) {
            for (Path file : files.filter(f -> f.toString().endsWith("Controller.java")).toList()) {
                String controller = file.getFileName().toString().replace("Controller.java", "");
                if (!ACTING_ON_ORDERS.contains(controller)) {
                    continue;
                }
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher mapping = WRITE_MAPPING.matcher(source);
                while (mapping.find()) {
                    Matcher endpoint = ENDPOINT.matcher(source);
                    if (endpoint.find(mapping.end())) {
                        endpoints.add(controller + "#" + nameOf(endpoint.group()));
                    }
                }
            }
        }
        return endpoints;
    }

    private static Map<String, Path> services() throws IOException {
        Map<String, Path> services = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SERVICES)) {
            files.filter(f -> f.toString().endsWith(".java"))
                .forEach(f -> services.put(f.getFileName().toString().replace(".java", ""), f));
        }
        return services;
    }

    private static String nameOf(String declaration) {
        String[] words = declaration.substring(0, declaration.length() - 1).strip().split("\\s++");
        return words[words.length - 1];
    }

    private static String bodyOf(String source, String method) {
        Matcher m = ANY_METHOD.matcher(source);
        StringBuilder bodies = new StringBuilder();
        while (m.find()) {
            if (!method.equals(nameOf(m.group()))) {
                continue;
            }
            int close = SourceScan.closingParen(source, m.end() - 1);
            int open = source.indexOf('{', close);
            int semi = source.indexOf(';', close);
            if (open >= 0 && (semi < 0 || open < semi)) {
                bodies.append(source, open, SourceScan.closingBrace(source, open));
            }
        }
        return bodies.isEmpty() ? null : bodies.toString();
    }
}
