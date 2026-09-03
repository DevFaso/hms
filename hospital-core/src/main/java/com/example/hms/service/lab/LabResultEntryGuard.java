package com.example.hms.service.lab;

import com.example.hms.exception.UnauthorizedAccessException;
import com.example.hms.model.LabTestDefinition;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides whether the current caller may enter or amend a result for one
 * specific test.
 *
 * <p>This cannot live in {@code @PreAuthorize}: the annotation runs before the
 * test definition is loaded, and the whole point is that the answer depends on
 * the test. The annotation keeps its broad role check; this makes the real
 * call once the row is in hand.
 */
@Component
public class LabResultEntryGuard {

    /**
     * Whether to restrict bedside roles to point-of-care tests.
     *
     * <p><b>Default false, and that is deliberate.</b> In a real ward a nurse
     * takes a glucometer reading and charts it; nobody waits for an
     * administrator to tick a box first. Shipping this on would have left
     * every ward unable to record anything until somebody marked their
     * catalogue — a regression in day-one capability for no clinical gain.
     *
     * <p>What makes off-by-default safe here is that it does NOT gate the part
     * that mattered. The dangerous behaviour was a nurse RELEASING a
     * laboratory-performed result — attesting to a number they never produced
     * — and {@link LabResultAuthority#RELEASE_ROLES} closes that
     * unconditionally, config or no config. This flag only governs the finer
     * question of who may type a result in, which is a catalogue decision each
     * hospital makes once it has marked its bedside tests.
     *
     * <p>Turn on with {@code hms.lab.point-of-care-enforcement.enabled=true}
     * after marking them.
     */
    @Value("${hms.lab.point-of-care-enforcement.enabled:false}")
    private boolean pointOfCareEnforcementEnabled;

    /**
     * Throw unless the caller may record a result for {@code test}.
     *
     * @param test the test the result belongs to; a null test is treated as
     *             laboratory-only, because a result that cannot name its test
     *             is not something a bedside device produced
     */
    public void requireMayEnterResult(LabTestDefinition test) {
        Set<String> authorities = currentAuthorities();

        // Laboratory staff enter anything. They are the ones who ran it.
        if (containsAny(authorities, LabResultAuthority.LABORATORY_ROLES)) {
            return;
        }
        // A real super-admin is unscoped by design across this product.
        if (authorities.contains("ROLE_SUPER_ADMIN")) {
            return;
        }

        boolean bedsideRole = containsAny(authorities, LabResultAuthority.POINT_OF_CARE_ROLES);
        if (bedsideRole && test != null && test.isPointOfCare()) {
            return;
        }
        // Until a hospital has marked its bedside tests, a nurse charting a
        // reading they just took must not be blocked by a flag nobody has set.
        // The release gate above is what protects the record; this is the
        // refinement, and it waits to be asked for.
        if (bedsideRole && !pointOfCareEnforcementEnabled) {
            return;
        }

        // The message names the reason rather than saying "forbidden", because
        // the fix is usually an administrator marking the test, not a
        // permission change — and a ward that cannot tell those apart assumes
        // the system is broken.
        if (bedsideRole) {
            throw new UnauthorizedAccessException(
                "Only laboratory staff may enter a result for "
                    + (test != null ? test.getName() : "this test")
                    + ". If this test is performed at the bedside, ask an administrator "
                    + "to mark it as point-of-care.");
        }
        throw new UnauthorizedAccessException("You may not enter laboratory results.");
    }

    private Set<String> currentAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());
    }

    private boolean containsAny(Set<String> held, Set<String> wanted) {
        return held.stream().anyMatch(wanted::contains);
    }
}
