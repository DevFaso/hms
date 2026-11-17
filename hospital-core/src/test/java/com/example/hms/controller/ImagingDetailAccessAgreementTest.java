package com.example.hms.controller;

import com.example.hms.config.PreAuthorizeMatcherPairingTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D856: a midwife listed imaging orders on /imaging, clicked an order's
 * detail icon, and the detail's report history (GET
 * /imaging/results/order/{id}/all) answered 403, so the portal went to its 403
 * page. The order reads admitted MIDWIFE; the report reads did not.
 *
 * <p>The class of defect: an action offered on a list the role may read must be
 * readable by the same role. So every staff role that may list imaging orders
 * must also read an order's reports (latest and history) and the order itself.
 * A role added to the order list without the report reads fails here.
 */
class ImagingDetailAccessAgreementTest {

    private static final String PATIENT = "ROLE_PATIENT";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"getAllReportsForOrder", "getLatestReportForOrder", "getReport"})
    @DisplayName("every staff role that lists imaging orders reads the order's reports")
    void reportReadsAdmitEveryOrderLister(String reportRead) {
        Set<String> listers = staffRoles(ImagingOrderController.class, "getAllOrders");
        listers.addAll(staffRoles(ImagingOrderController.class, "getOrdersByHospital"));
        Set<String> readers = staffRoles(ImagingResultController.class, reportRead);

        assertThat(listers).as("the scan read the order list guard").contains("ROLE_MIDWIFE");
        assertThat(readers).as("ImagingResultController#" + reportRead + " must admit every order lister")
            .containsAll(listers);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"getAllOrders", "getOrdersByHospital"})
    @DisplayName("every staff role that lists imaging orders opens one order")
    void orderDetailAdmitsEveryOrderLister(String list) {
        assertThat(staffRoles(ImagingOrderController.class, "getOrder"))
            .containsAll(staffRoles(ImagingOrderController.class, list));
    }

    private static Set<String> staffRoles(Class<?> controller, String methodName) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
            .filter(m -> m.getName().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(controller.getSimpleName() + "#" + methodName + " is gone"));
        PreAuthorize guard = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        assertThat(guard).as(methodName + " carries its own @PreAuthorize").isNotNull();
        Set<String> roles = new LinkedHashSet<>(PreAuthorizeMatcherPairingTest.rolesAdmittedBy(guard.value()));
        roles.remove(PATIENT);
        return roles;
    }
}
