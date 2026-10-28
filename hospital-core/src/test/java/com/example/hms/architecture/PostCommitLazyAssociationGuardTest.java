package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D921 (and #564 before it) guard: code that runs with no persistence
 * context must not walk a lazy association.
 *
 * <p>A controller method is not transactional; a lambda handed to any
 * {@code afterCommit(...)} (the {@code TransactionCallbacks} helper or a
 * service's own wrapper), a {@code registerSynchronization(...)} argument, a
 * {@code @TransactionalEventListener} and an interceptor's or synchronization's
 * {@code afterCompletion}/{@code afterCommit} override all run after the
 * session closed. In each, the first {@code entity.getHospital().getName()} on
 * an entity loaded earlier is a {@code LazyInitializationException}: #564 lost
 * every hospital-scoped write audit for a week that way, and D921 turned every
 * emailed activation link into a 500 that left the account half-activated.
 * Pass ids; let the REQUIRES_NEW service load what it needs (memory note
 * "out-of-session lazy proxies").
 *
 * <p>The scan looks for a two-step getter chain through an association
 * ({@code .getRole().getCode()}, {@code .getHospital().getId()}, ...) in
 * <ul>
 *   <li>every file under {@code controller/} (comments stripped);</li>
 *   <li>the whole argument of every {@code afterCommit(} and
 *       {@code registerSynchronization(} call;</li>
 *   <li>the body of every {@code @TransactionalEventListener} method (whatever
 *       its modifier, however many lines the annotation spans) and of every
 *       {@code afterCompletion(...)}/{@code afterCommit(...)} method.</li>
 * </ul>
 * The post-commit code holds none today and must stay at none. The controller
 * sites that exist are listed with their reason and may only shrink;
 * {@code .getX().getId()} on a row the same method just loaded by id is
 * usually safe (the proxy is initialised by the load), which is why most of
 * them are tolerated rather than fixed — but D921's two are not.
 */
@DisplayName("No lazy association is walked outside a persistence context")
class PostCommitLazyAssociationGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final String CONTROLLERS = "controller/";

    /** Association getters the entity model declares LAZY (or that are ManyToOne by default). */
    static final Pattern CHAIN = Pattern.compile(
        "\\.(getRole|getHospital|getUser|getPatient|getStaff|getEncounter|getAssignment|getDepartment"
            + "|getPrescription|getOrder|getLabOrder|getInvoice|getAdmission|getAppointment|getPerformedByUser"
            + "|getCreatedBy|getProvider|getOrganization|getParent|getPrimaryHospital|getRequestedBy"
            + "|getPhysician|getNurse|getDoctor|getAuthor)\\(\\)\\s*\\.\\s*get\\w+\\(");

    /**
     * In post-commit code ANY two-step getter chain is suspect, not only the
     * {@link #CHAIN} list the controllers are measured against: the entity model
     * declares some two hundred associations and the lambda must stay at zero
     * whatever their names. {@code getClass().getSimpleName()} and friends are the
     * one chain that reads no row.
     */
    static final Pattern ANY_CHAIN = Pattern.compile(
        "\\.(?!getClass\\(\\))get[A-Z]\\w*\\(\\)\\s*\\.\\s*get\\w+\\(");

    /** A post-commit callback handed to any wrapper: the `(` of the call. */
    private static final Pattern POST_COMMIT_CALL = Pattern.compile("\\b(?:afterCommit|registerSynchronization)\\(");

    /** A listener annotation; its arguments (if any) are skipped by balanced parens before the body is found. */
    private static final Pattern LISTENER_ANNOTATION = Pattern.compile("@TransactionalEventListener\\b");

    /** An interceptor's or TransactionSynchronization's override: the `(` of its parameter list. */
    private static final Pattern OVERRIDE = Pattern.compile("\\bvoid\\s+(?:afterCompletion|afterCommit)\\s*\\(");

    private record Allowance(int count, String reason) { }

    private static Map<String, Allowance> allow(Object... fileChainCountReason) {
        Map<String, Allowance> out = new TreeMap<>();
        for (int i = 0; i < fileChainCountReason.length; i += 4) {
            out.put(fileChainCountReason[i] + " " + fileChainCountReason[i + 1],
                new Allowance((Integer) fileChainCountReason[i + 2], (String) fileChainCountReason[i + 3]));
        }
        return out;
    }

    private static final String LOADED_HERE =
        "chain on a row the same handler loaded by id; the proxy is initialised. Move to the service when touched";
    private static final String D921 =
        "QA D921: verifyEmail walks assignment.getRole()/getHospital() after userRepository.save() with no"
            + " transaction — the lazy load fails and the patient assignment stays inactive";

    /** Controller chains as they stand. Shrink only. */
    private static final Map<String, Allowance> ALLOWED_IN_CONTROLLERS = allow(
        "controller/AuthController.java", ".getRole().getCode(", 2, D921 + " (one of the two is line ~969, loaded here)",
        "controller/AuthController.java", ".getHospital().getLifecycleState(", 1, D921,
        "controller/AuthController.java", ".getHospital().getId(", 2, LOADED_HERE,
        "controller/AuthController.java", ".getHospital().getName(", 1, LOADED_HERE,
        "controller/CredentialingController.java", ".getStaff().getId(", 1, LOADED_HERE,
        "controller/LookupController.java", ".getUser().getEmail(", 1, LOADED_HERE,
        "controller/MfaController.java", ".getRole().getName(", 1, LOADED_HERE,
        "controller/MfaController.java", ".getRole().getCode(", 1, LOADED_HERE,
        "controller/OrganizationSecurityController.java", ".getOrganization().getId(", 2, LOADED_HERE,
        "controller/ReportController.java", ".getHospital().getId(", 1, LOADED_HERE,
        "controller/integration/Dhis2ExportController.java", ".getHospital().getId(", 3, LOADED_HERE);

    static Map<String, Integer> controllerChains(String rawSource) {
        Map<String, Integer> counts = new TreeMap<>();
        Matcher m = CHAIN.matcher(SourceScan.stripComments(rawSource));
        while (m.find()) {
            counts.merge(normalise(m.group()), 1, Integer::sum);
        }
        return counts;
    }

    private static String normalise(String chain) {
        return chain.replaceAll("\\s+", "");
    }

    /** Every chain inside a post-commit argument, a listener body or an override body. */
    static List<String> postCommitChains(String file, String rawSource) {
        String source = SourceScan.stripComments(rawSource);
        List<String> out = new ArrayList<>();
        Matcher call = POST_COMMIT_CALL.matcher(source);
        while (call.find()) {
            int open = call.end() - 1;
            report(file, source, open, SourceScan.closingParen(source, open), out);
        }
        Matcher listener = LISTENER_ANNOTATION.matcher(source);
        while (listener.find()) {
            int at = listener.end();
            while (at < source.length() && Character.isWhitespace(source.charAt(at))) {
                at++;
            }
            if (at < source.length() && source.charAt(at) == '(') {
                at = SourceScan.closingParen(source, at) + 1;
            }
            int open = source.indexOf('{', at);
            if (open >= 0) {
                report(file, source, open, SourceScan.closingBrace(source, open), out);
            }
        }
        Matcher override = OVERRIDE.matcher(source);
        while (override.find()) {
            int params = override.end() - 1;
            int open = source.indexOf('{', SourceScan.closingParen(source, params));
            if (open >= 0) {
                report(file, source, open, SourceScan.closingBrace(source, open), out);
            }
        }
        return out;
    }

    private static void report(String file, String source, int open, int close, List<String> out) {
        Matcher c = ANY_CHAIN.matcher(source.substring(open, close));
        while (c.find()) {
            out.add(file + ":" + SourceScan.lineOf(source, open + c.start()) + ": " + normalise(c.group())
                + " in post-commit code (no session: pass ids)");
        }
    }

    @Test
    @DisplayName("no post-commit callback, transactional event listener or afterCompletion walks an association")
    void postCommitCodePassesIds() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                offenders.addAll(postCommitChains(rel, Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        assertThat(offenders)
            .as("post-commit code runs with no persistence context: pass ids to the REQUIRES_NEW service (#564, D921)"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("controllers walk no association beyond the listed sites, and the list only shrinks")
    void controllersStayWithinTheAllowance() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                if (!rel.startsWith(CONTROLLERS)) {
                    continue;
                }
                controllerChains(Files.readString(p, StandardCharsets.UTF_8))
                    .forEach((chain, n) -> actual.put(rel + " " + chain, n));
            }
        }
        List<String> fresh = new ArrayList<>();
        actual.forEach((site, n) -> {
            int allowed = ALLOWED_IN_CONTROLLERS.containsKey(site) ? ALLOWED_IN_CONTROLLERS.get(site).count() : 0;
            if (n > allowed) {
                fresh.add(site + " ×" + n + " (allowed " + allowed + ")");
            }
        });
        assertThat(fresh)
            .as("a controller walked an association: return a DTO from the service, or pass ids (QA D921)"
                + SourceScan.MANDATE)
            .isEmpty();

        List<String> stale = new ArrayList<>();
        ALLOWED_IN_CONTROLLERS.forEach((site, allowance) -> {
            int n = actual.getOrDefault(site, 0);
            if (n < allowance.count()) {
                stale.add(site + ": " + n + " left, " + allowance.count() + " allowed — lower it");
            }
        });
        assertThat(stale).as("a site was fixed: shrink its allowance in the same change" + SourceScan.MANDATE).isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D921 and #564 shapes, through wrappers and multi-line annotations, not comments")
    void theScanCatchesTheOldShapes() {
        String d921 = """
            userRepository.save(user);
            // was: assignment.getRole().getCode() — a comment is not a chain
            for (var assignment : assignmentRepository.findByUserId(user.getId())) {
                if ("ROLE_PATIENT".equalsIgnoreCase(assignment.getRole().getCode())
                        && !isClosed(assignment.getHospital().getLifecycleState())) {
            """;
        assertThat(controllerChains(d921)).containsOnly(
            Map.entry(".getRole().getCode(", 1), Map.entry(".getHospital().getLifecycleState(", 1));

        String pr564 = """
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
                String hospitalName = assignment.getHospital().getName();
                audit.record(hospitalName);
            }
            private void auditAfterCommit(Assignment assignment) {
                TransactionCallbacks.afterCommit(() -> {
                    log.info(assignment.getHospital().getName());
                });
            }
            @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
            public void onCreated(UserCreatedEvent event) {
                mail.send(event.user().getHospital().getName());
            }
            @TransactionalEventListener(
                phase = TransactionPhase.AFTER_COMMIT,
                fallbackExecution = true)
            void onArchived(ArchivedEvent event) {
                mail.send(event.row().getHospital().getName());
            }
            private void fine(Assignment assignment) {
                UUID hospitalId = assignment.getHospital().getId();
                TransactionCallbacks.afterCommit(() -> audit.record(hospitalId));
            }
            private void expressionLambda(Assignment assignment) {
                TransactionCallbacks.afterCommit(() -> mail.send(assignment.getHospital().getName()));
            }
            private void ownWrapper(Prescription rx) {
                afterCommit(rx.getId(), "sms", () -> writer.write(rx.getPatient().getId(), event));
            }
            private void synchronization(Dispense saved) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        postDeliveryCharge(saved.getPrescription().getId());
                    }
                });
            }
            private void unlistedAssociation(Referral referral) {
                TransactionCallbacks.afterCommit(() -> sms.send(referral.getReferringProvider().getPhone()));
                TransactionCallbacks.afterCommit(() -> log.info(referral.getClass().getSimpleName()));
            }
            """;
        assertThat(postCommitChains("x", pr564)).hasSize(9);
    }
}
