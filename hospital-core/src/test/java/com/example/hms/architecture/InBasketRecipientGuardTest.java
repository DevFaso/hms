package com.example.hms.architecture;

import com.example.hms.controller.InBasketController;
import com.example.hms.controller.LabOrderController;
import com.example.hms.controller.TransfusionController;
import com.example.hms.security.RoleExpansion;
import com.example.hms.service.LabResultServiceImpl;
import com.example.hms.service.impl.TransfusionServiceImpl;
import com.example.hms.service.maternity.ObstetricAlertEscalationWorker;
import com.example.hms.service.support.EmergencyReleasePolicy;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1461 (round 35): the urgent review of a serologically incompatible release was filed for each
 * lab director, and the lab director could not open the In-Basket. The item was stored, nobody read
 * it, and nothing said so.
 *
 * <p>Every class that files an item names the roles it files for; each of those roles must get through
 * the controller, the portal's route and the portal's nav link. A new sender fails here until it is
 * added to {@link #RECIPIENTS}. The role sets are read from the code that picks the recipients, so a
 * sender that widens its circle fails here too.
 */
class InBasketRecipientGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path ROUTES = Path.of("../hospital-portal/src/app/app.routes.ts");
    private static final Path SHELL = Path.of("../hospital-portal/src/app/shell/shell.ts");

    private static final Pattern FILES_AN_ITEM = Pattern.compile(
        "\\.createItem(?:InNewTransaction)?\\(|InBasketItem\\.builder\\(|inBasketItemRepository\\.save\\(");
    private static final Pattern QUOTED_ROLE = Pattern.compile("'(?:ROLE_)?([A-Z_]+)'");

    /** Every class that files an item, by file name, and whom. */
    private static final Map<String, Set<String>> RECIPIENTS = Map.of(
        // The requester, who ordered it; the nurse who ran the bag; the lab directors; the authoriser.
        "TransfusionServiceImpl.java", union(
            rolesOf(constant(TransfusionController.class, "PRESCRIBER")),
            rolesOf(constant(TransfusionController.class, "BEDSIDE")),
            codes(setConstant(TransfusionServiceImpl.class, "LAB_DIRECTOR_ROLE_CODES")),
            codes(EmergencyReleasePolicy.INCOMPATIBLE_AUTHORISER_ROLE_CODES)),
        // The clinician who ordered the test.
        "LabResultServiceImpl.java", labOrderers(),
        "LabSpecimenRejectionNotifier.java", labOrderers(),
        // The obstetric team, then the ward.
        "ObstetricAlertEscalationWorker.java", union(
            codes(setConstant(ObstetricAlertEscalationWorker.class, "FIRST_NOTICE_ROLES")),
            codes(setConstant(ObstetricAlertEscalationWorker.class, "REMINDER_ROLES"))),
        // The urgent dispute of a death: the administrators of both hospitals, who decide it.
        "DeathDisputeServiceImpl.java", codes(setConstant(com.example.hms.service.impl.DeathDisputeServiceImpl.class, "ADMIN_ROLE_CODES")),
        // The treating clinicians of a running treatment, who assess it.
        "OrderReviewHoldServiceImpl.java", union(
            rolesOf(constant(TransfusionController.class, "PRESCRIBER")),
            rolesOf(constant(TransfusionController.class, "BEDSIDE"))),
        // The store itself: every sender above reaches it through createItem.
        "InBasketServiceImpl.java", Set.of());

    /** The exception to E9 #67 stays one exception: no other sender files an item for a hospital administrator. */
    @Test
    void onlyTheDisputeReviewIsFiledForAHospitalAdministrator() {
        List<String> senders = new ArrayList<>();
        RECIPIENTS.forEach((sender, roles) -> {
            if (roles.stream().anyMatch(role -> role.contains("HOSPITAL_ADMIN"))) {
                senders.add(sender);
            }
        });
        org.assertj.core.api.Assertions.assertThat(senders).containsExactly("DeathDisputeServiceImpl.java");
    }

    @Test
    void everyClassThatFilesAnItemIsListedWithItsRecipients() throws IOException {
        List<String> senders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (FILES_AN_ITEM.matcher(code).find()) {
                    senders.add(file.getFileName().toString());
                }
            }
        }
        assertThat(senders)
            .as("a class files an in-basket item: name the roles it files for in RECIPIENTS (QA D1461)"
                + SourceScan.MANDATE)
            .containsExactlyInAnyOrderElementsOf(RECIPIENTS.keySet());
    }

    @Test
    void everyMappingOfTheControllerAdmitsEveryRecipient() {
        List<String> shut = new ArrayList<>();
        for (Method method : InBasketController.class.getDeclaredMethods()) {
            PreAuthorize guard = method.getAnnotation(PreAuthorize.class);
            if (guard == null) {
                continue;
            }
            Set<String> admitted = rolesOf(guard.value());
            RECIPIENTS.forEach((sender, roles) -> roles.stream()
                .filter(role -> !admits(admitted, role))
                .forEach(role -> shut.add(method.getName() + " shuts out " + role + ", filed by " + sender)));
        }
        assertThat(shut)
            .as("an in-basket item is filed for a role that cannot read it (QA D1461)" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void thePortalRouteAndNavLinkAdmitEveryRecipient() throws IOException {
        Set<String> route = rolesAfter(ROUTES, "path: 'in-basket'");
        Set<String> nav = rolesAfter(SHELL, "route: '/in-basket'");
        List<String> shut = new ArrayList<>();
        RECIPIENTS.forEach((sender, roles) -> roles.forEach(role -> {
            if (!admits(route, role)) {
                shut.add("the route shuts out " + role + ", filed by " + sender);
            }
            if (!admits(nav, role)) {
                shut.add("the nav link hides from " + role + ", filed by " + sender);
            }
        }));
        assertThat(shut)
            .as("an in-basket item is filed for a role the portal does not take there (QA D1461)"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void theLabDirectorIsARecipientTheGuardReads() {
        assertThat(RECIPIENTS.get("TransfusionServiceImpl.java"))
            .contains("LAB_DIRECTOR", "DOCTOR", "NURSE", "MIDWIFE");
        assertThat(RECIPIENTS.get("LabResultServiceImpl.java")).contains("DOCTOR", "NURSE");
        assertThat(RECIPIENTS.get("ObstetricAlertEscalationWorker.java"))
            .contains("DOCTOR", "MIDWIFE", "NURSE");
        assertThat(LabResultServiceImpl.class).isNotNull();
    }

    /** A physician or a surgeon IS a doctor (RoleExpansion), on the server and in the portal. */
    private static boolean admits(Set<String> admitted, String role) {
        return admitted.contains(role)
            || (admitted.contains("DOCTOR") && RoleExpansion.expand(List.of("ROLE_" + role)).contains("ROLE_DOCTOR"));
    }

    private static Set<String> labOrderers() {
        for (Method method : LabOrderController.class.getDeclaredMethods()) {
            if ("createLabOrder".equals(method.getName())) {
                return rolesOf(method.getAnnotation(PreAuthorize.class).value());
            }
        }
        throw new IllegalStateException("LabOrderController#createLabOrder is gone");
    }

    private static Set<String> rolesAfter(Path file, String anchor) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        int at = text.indexOf(anchor);
        assertThat(at).as(anchor + " in " + file).isNotNegative();
        int open = text.indexOf("roles: [", at);
        return rolesOf(text.substring(open, text.indexOf(']', open)));
    }

    private static Set<String> rolesOf(String expression) {
        Set<String> roles = new TreeSet<>();
        Matcher matcher = QUOTED_ROLE.matcher(expression);
        while (matcher.find()) {
            roles.add(matcher.group(1));
        }
        return roles;
    }

    private static Set<String> codes(Collection<String> roles) {
        Set<String> out = new TreeSet<>();
        roles.forEach(role -> out.add(role.replaceFirst("^ROLE_", "")));
        return out;
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> out = new TreeSet<>();
        Arrays.stream(sets).forEach(out::addAll);
        return out;
    }

    private static String constant(Class<?> type, String name) {
        return (String) read(type, name);
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> setConstant(Class<?> type, String name) {
        return (Collection<String>) read(type, name);
    }

    private static Object read(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getSimpleName() + "#" + name + " is gone: the guard reads whom it picks", e);
        }
    }
}
