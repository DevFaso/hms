package com.example.hms.architecture;

import com.example.hms.controller.DicomProxyController;
import com.example.hms.controller.ImagingOrderController;
import com.example.hms.controller.ImagingResultController;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1841 (class: one imaging read refused a role every sibling read admits). GET /imaging/orders
 * left out RADIOLOGIST while /orders/{id}, /orders/hospital/{id} and /orders/patient/{id} admitted
 * him, so the radiologist's dashboard and worklist answered 403 and the portal left for /error/403.
 *
 * <ul>
 *   <li>Every imaging read ({@code @GetMapping} on the imaging controllers) admits RADIOLOGIST,
 *       except the reads listed in {@link #NOT_A_RADIOLOGIST_READ} with their reason.</li>
 *   <li>The three order reads a worklist is built from admit the same roles.</li>
 *   <li>Every role the portal's /imaging route lets in is one its load-time read admits.</li>
 * </ul>
 * Who may sign an order or a report is not a read and is not decided here.
 */
class ImagingReadRoleGuardTest {

    private static final List<Class<?>> IMAGING_CONTROLLERS =
        List.of(ImagingOrderController.class, ImagingResultController.class, DicomProxyController.class);

    /** Reads a radiologist has no use for, each with its reason. A new entry needs one too. */
    private static final Map<String, String> NOT_A_RADIOLOGIST_READ = Map.of(
        "ImagingOrderController.getDuplicatePreview",
        "the ordering form's duplicate check, run before an order is placed; the radiologist does not order");

    private static final Pattern QUOTED_ROLE = Pattern.compile("'(?:ROLE_)?([A-Z_]+)'");
    private static final Path ROUTES = Path.of("../hospital-portal/src/app/app.routes.ts");

    @Test
    void everyImagingReadAdmitsTheRadiologist() {
        List<String> refused = new ArrayList<>();
        int reads = 0;
        for (Class<?> controller : IMAGING_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(GetMapping.class)) {
                    continue;
                }
                reads++;
                String name = controller.getSimpleName() + "." + method.getName();
                Set<String> roles = roles(controller, method);
                if (!roles.contains("RADIOLOGIST") && !NOT_A_RADIOLOGIST_READ.containsKey(name)) {
                    refused.add(name + " " + roles);
                }
            }
        }
        assertThat(reads).as("the imaging reads were found").isGreaterThan(5);
        assertThat(refused).as("an imaging read that refuses the radiologist (QA D1841)" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void theWorklistOrderReadsAdmitTheSameRoles() throws NoSuchMethodException {
        Set<String> all = roles(ImagingOrderController.class, method(ImagingOrderController.class, "getAllOrders"));
        Set<String> byHospital = roles(ImagingOrderController.class,
            method(ImagingOrderController.class, "getOrdersByHospital"));
        Set<String> one = roles(ImagingOrderController.class, method(ImagingOrderController.class, "getOrder"));
        assertThat(all).as("GET /imaging/orders and /orders/hospital/{id} (QA D1841)" + SourceScan.MANDATE)
            .isEqualTo(byHospital);
        assertThat(one).as("GET /imaging/orders and /orders/{id} (QA D1841)" + SourceScan.MANDATE).isEqualTo(all);
    }

    @Test
    void everyRoleThePortalImagingRouteAdmitsCanLoadIt() throws IOException, NoSuchMethodException {
        String routes = Files.readString(ROUTES, StandardCharsets.UTF_8);
        int path = routes.indexOf("path: 'imaging',");
        assertThat(path).as("the /imaging route").isNotNegative();
        int open = routes.indexOf("roles: [", path);
        String block = routes.substring(open, routes.indexOf(']', open));
        Set<String> shownTo = new TreeSet<>();
        Matcher matcher = QUOTED_ROLE.matcher(block);
        while (matcher.find()) {
            shownTo.add(matcher.group(1));
        }
        assertThat(shownTo).as("the /imaging route's roles were read").contains("RADIOLOGIST");
        Set<String> admitted = roles(ImagingOrderController.class, method(ImagingOrderController.class, "getAllOrders"));
        assertThat(admitted).as("a role the /imaging route admits but its load-time read GET /imaging/orders"
            + " refuses (QA D1841)" + SourceScan.MANDATE).containsAll(shownTo);
    }

    private static Method method(Class<?> controller, String name) throws NoSuchMethodException {
        for (Method method : controller.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new NoSuchMethodException(controller.getSimpleName() + "." + name);
    }

    /** The roles the method's gate names, or the class gate when the method has none. */
    private static Set<String> roles(Class<?> controller, Method method) {
        PreAuthorize gate = method.getAnnotation(PreAuthorize.class);
        if (gate == null) {
            gate = controller.getAnnotation(PreAuthorize.class);
        }
        assertThat(gate).as(controller.getSimpleName() + "." + method.getName() + " has a gate").isNotNull();
        Set<String> roles = new TreeSet<>();
        Matcher matcher = QUOTED_ROLE.matcher(gate.value());
        while (matcher.find()) {
            roles.add(matcher.group(1));
        }
        return roles;
    }
}
