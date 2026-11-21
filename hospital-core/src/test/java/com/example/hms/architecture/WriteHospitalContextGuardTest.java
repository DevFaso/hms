package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ValueConstants;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * r66 D2901: the hospital of a write is the caller's acting hospital, resolved by the server.
 *
 * <p>The medication catalogue's editor could not save or deactivate anything for seven months: the portal
 * names no hospital on its writes (the acting hospital travels as the request's scope), while
 * {@code PUT /medication-catalog/{id}} looked the row up at the body's (absent) hospital, 404, and
 * {@code DELETE /medication-catalog/{id}} declared {@code hospitalId} a REQUIRED request parameter, 400.
 * The pharmacy registry's delete had the same required parameter. A hospital admin could therefore not
 * tag an antiretroviral as sensitive from the product.
 *
 * <p>The rule this guard holds for every controller at once: a handler that WRITES (POST, PUT, PATCH,
 * DELETE) never REQUIRES a {@code hospitalId} request parameter. It may accept one
 * ({@code required = false}; {@code HospitalIdNarrowingInterceptor} narrows the scope to it and refuses a
 * hospital the caller may not act at), and resolves the acting hospital itself when none is sent
 * ({@code ControllerAuthUtils.resolveHospitalScope} or {@code RoleValidator.requireActiveHospitalId}).
 *
 * <p>The behaviour is in MedicationCatalogItemServiceImplTest, MedicationCatalogControllerTest,
 * PharmacyRegistryControllerTest and the portal's medication-catalog.spec.ts.
 */
class WriteHospitalContextGuardTest {

    private static final String HOSPITAL_ID = "hospitalId";

    private static final Set<RequestMethod> WRITES =
        Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    /**
     * Writes whose caller always names the hospital, each with its reason. A new entry needs the client
     * call that sends it, checked.
     */
    private static final Map<String, String> CALLER_NAMES_THE_HOSPITAL = Map.of(
        "Dhis2AdminController.upsertFacility",
        "the portal's Dhis2Service.upsertFacilityConfig sets ?hospitalId from the facility the admin picked",
        "Dhis2AdminController.createMapping",
        "the portal's Dhis2Service.createMapping sets ?hospitalId from the facility the admin picked",
        "Dhis2AdminController.updateMapping",
        "the portal's Dhis2Service.updateMapping sets ?hospitalId from the facility the admin picked",
        "Dhis2AdminController.deleteMapping",
        "the portal's Dhis2Service.deleteMapping sets ?hospitalId from the facility the admin picked");

    private static final DefaultParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    @Test
    void noWriteHandlerRequiresAHospitalIdRequestParameter() throws ClassNotFoundException {
        Set<String> offenders = new TreeSet<>();
        Set<String> seen = new TreeSet<>();
        int writes = 0;
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition definition : scanner.findCandidateComponents("com.example.hms")) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            for (Method method : controller.getDeclaredMethods()) {
                if (!isWrite(method)) {
                    continue;
                }
                writes++;
                if (requiresHospitalIdParameter(method)) {
                    String site = controller.getSimpleName() + "." + method.getName();
                    seen.add(site);
                    if (!CALLER_NAMES_THE_HOSPITAL.containsKey(site)) {
                        offenders.add(site);
                    }
                }
            }
        }

        assertThat(writes).as("the scan found the write handlers").isGreaterThan(300);
        assertThat(offenders)
            .as("write handlers that REQUIRE a hospitalId request parameter: the portal names no hospital on a"
                + " write, so each of these answers 400 (r66 D2901). Declare it required = false and resolve the"
                + " acting hospital on the server" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(CALLER_NAMES_THE_HOSPITAL.keySet())
            .as("an allowlist entry whose handler no longer requires hospitalId is deleted")
            .isSubsetOf(seen);
    }

    private static boolean isWrite(Method method) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null) {
            return false;
        }
        for (RequestMethod requestMethod : mapping.method()) {
            if (WRITES.contains(requestMethod)) {
                return true;
            }
        }
        return false;
    }

    private static boolean requiresHospitalIdParameter(Method method) {
        String[] names = PARAMETER_NAMES.getParameterNames(method);
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            RequestParam requestParam = AnnotatedElementUtils.findMergedAnnotation(parameters[i], RequestParam.class);
            if (requestParam == null || !requestParam.required()
                || !ValueConstants.DEFAULT_NONE.equals(requestParam.defaultValue())
                || parameters[i].getType() == java.util.Optional.class) {
                continue;
            }
            String name = StringUtils.hasText(requestParam.name()) ? requestParam.name()
                : names != null ? names[i] : parameters[i].getName();
            if (HOSPITAL_ID.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
