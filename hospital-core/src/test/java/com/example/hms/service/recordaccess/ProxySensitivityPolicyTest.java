package com.example.hms.service.recordaccess;

import com.example.hms.enums.ProxyRelationship;
import com.example.hms.enums.SensitivityCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Policy 2026-11-20 (provisional): what a family proxy may read of a row that
 * carries a sensitive category.
 */
@DisplayName("Policy 2026-11-20: what a proxy reads of a sensitive row")
class ProxySensitivityPolicyTest {

    private static final int ADOLESCENT = ProxySensitivityPolicy.DEFAULT_ADOLESCENT_AGE;

    private static ProxyReadContext reader(ProxyRelationship relationship, boolean verified, String... scopes) {
        return new ProxyReadContext(UUID.randomUUID(), UUID.randomUUID(), relationship, verified, Set.of(scopes));
    }

    private static final ProxyReadContext VERIFIED_PARENT = reader(ProxyRelationship.PARENT, true, "ALL");
    private static final ProxyReadContext VERIFIED_GUARDIAN = reader(ProxyRelationship.LEGAL_GUARDIAN, true, "ALL");
    private static final ProxyReadContext DECLARED_PARENT = reader(ProxyRelationship.PARENT, false, "ALL");
    private static final ProxyReadContext SPOUSE = reader(ProxyRelationship.SPOUSE, true, "ALL");

    @Test
    @DisplayName("an untagged row is read by every proxy, and with no grant in hand")
    void anUntaggedRowIsOrdinary() {
        assertThat(ProxySensitivityPolicy.admits(null, 40, ADOLESCENT, SPOUSE)).isTrue();
        assertThat(ProxySensitivityPolicy.admits(null, 40, ADOLESCENT, null)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(SensitivityCategory.class)
    @DisplayName("every category of an adult is hidden from every proxy with « tout » (ALL), verified parent included")
    void anAdultsCategoryIsHidden(SensitivityCategory category) {
        assertThat(ProxySensitivityPolicy.admits(category, 18, ADOLESCENT, VERIFIED_PARENT)).isFalse();
        assertThat(ProxySensitivityPolicy.admits(category, 45, ADOLESCENT, SPOUSE)).isFalse();
        // An unknown age is read as an adult's.
        assertThat(ProxySensitivityPolicy.admits(category, null, ADOLESCENT, VERIFIED_PARENT)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(SensitivityCategory.class)
    @DisplayName("with no grant in hand, no tagged row is read")
    void noGrantNoTaggedRow(SensitivityCategory category) {
        assertThat(ProxySensitivityPolicy.admits(category, 5, ADOLESCENT, null)).isFalse();
    }

    @Test
    @DisplayName("tuberculosis is restricted: read with the explicit scope, hidden with « tout »")
    void tuberculosisNeedsItsOwnScope() {
        ProxyReadContext withScope = reader(ProxyRelationship.SPOUSE, false,
            "VIEW_RECORDS", ProxyReadContext.SCOPE_TUBERCULOSIS);
        assertThat(ProxySensitivityPolicy.admits(SensitivityCategory.TUBERCULOSIS, 40, ADOLESCENT, withScope)).isTrue();
        assertThat(ProxySensitivityPolicy.admits(SensitivityCategory.TUBERCULOSIS, 40, ADOLESCENT, SPOUSE)).isFalse();
        // The scope opens the restricted category only.
        assertThat(ProxySensitivityPolicy.admits(SensitivityCategory.HIV, 40, ADOLESCENT, withScope)).isFalse();
        assertThat(ProxySensitivityPolicy.admits(SensitivityCategory.STI, 40, ADOLESCENT, withScope)).isFalse();
    }

    @Test
    @DisplayName("a minor's HIV row is hidden from the verified parent at every age: a clinician releases it")
    void aMinorsHivIsReleaseOnly() {
        for (int age : new int[] {0, 5, 11, 12, 17}) {
            assertThat(ProxySensitivityPolicy.admits(SensitivityCategory.HIV, age, ADOLESCENT, VERIFIED_PARENT))
                .as("age %d", age).isFalse();
            assertThat(ProxySensitivityPolicy.admits(SensitivityCategory.SEXUAL_DOMESTIC_VIOLENCE, age, ADOLESCENT,
                VERIFIED_GUARDIAN)).as("age %d", age).isFalse();
        }
    }

    @Test
    @DisplayName("only an HIV result is releasable; violence on the visit or the test forbids it; nothing else is")
    void onlyHivIsReleasable() {
        assertThat(ProxySensitivityPolicy.releasable(null, SensitivityCategory.HIV)).isTrue();
        assertThat(ProxySensitivityPolicy.releasable(SensitivityCategory.HIV, null)).isTrue();
        // An HIV test ordered in an STI-tagged visit is still an HIV result.
        assertThat(ProxySensitivityPolicy.releasable(SensitivityCategory.STI, SensitivityCategory.HIV)).isTrue();
        assertThat(ProxySensitivityPolicy.releasable(SensitivityCategory.SEXUAL_DOMESTIC_VIOLENCE,
            SensitivityCategory.HIV)).as("a post-assault HIV test").isFalse();
        assertThat(ProxySensitivityPolicy.releasable(SensitivityCategory.HIV,
            SensitivityCategory.SEXUAL_DOMESTIC_VIOLENCE)).isFalse();
        assertThat(ProxySensitivityPolicy.releasable(null, null)).isFalse();
        for (SensitivityCategory category : SensitivityCategory.values()) {
            if (category != SensitivityCategory.HIV) {
                assertThat(ProxySensitivityPolicy.releasable(null, category)).as("%s", category).isFalse();
                assertThat(ProxySensitivityPolicy.releasable(category, null)).as("%s", category).isFalse();
            }
        }
        assertThat(ProxySensitivityPolicy.minorRuleOf(SensitivityCategory.SEXUAL_DOMESTIC_VIOLENCE))
            .isEqualTo(ProxySensitivityPolicy.MinorRule.NEVER);
        assertThat(ProxySensitivityPolicy.minorRuleOf(SensitivityCategory.HIV))
            .isEqualTo(ProxySensitivityPolicy.MinorRule.RELEASE_ONLY);
    }

    @Test
    @DisplayName("round 2: a test ordered in an HIV visit is judged by its own category, never made releasable by the visit")
    void aTestInAnHivVisitIsJudgedByItsOwnCategory() {
        assertThat(ProxySensitivityPolicy.releasable(SensitivityCategory.HIV, SensitivityCategory.PREGNANCY))
            .as("a β-hCG ordered in the HIV clinic").isFalse();
        assertThat(ProxySensitivityPolicy.releasable(SensitivityCategory.HIV, SensitivityCategory.STI))
            .as("a TPHA ordered in the HIV clinic").isFalse();
        // An untagged test of an HIV visit (a CD4 count) is an HIV result; an HIV test of an STI visit too.
        assertThat(ProxySensitivityPolicy.releasable(SensitivityCategory.HIV, null)).isTrue();
        assertThat(ProxySensitivityPolicy.releasable(SensitivityCategory.STI, SensitivityCategory.HIV)).isTrue();
        // The whole table: no violence anywhere, then the test's category if it has one, else the visit's.
        java.util.List<SensitivityCategory> all = new java.util.ArrayList<>(java.util.Arrays.asList(
            SensitivityCategory.values()));
        all.add(null);
        for (SensitivityCategory visit : all) {
            for (SensitivityCategory test : all) {
                boolean violence = visit == SensitivityCategory.SEXUAL_DOMESTIC_VIOLENCE
                    || test == SensitivityCategory.SEXUAL_DOMESTIC_VIOLENCE;
                SensitivityCategory deciding = test != null ? test : visit;
                assertThat(ProxySensitivityPolicy.releasable(visit, test))
                    .as("visit %s, test %s", visit, test)
                    .isEqualTo(!violence && deciding == SensitivityCategory.HIV);
            }
        }
    }

    @Test
    @DisplayName("what the clinician's list calls hidden is exactly what a verified parent is refused")
    void hiddenFromVerifiedParentIsTheReadRule() {
        for (SensitivityCategory category : SensitivityCategory.values()) {
            for (Integer age : new Integer[] {null, 0, 6, 11, 12, 15, 17, 18, 40}) {
                for (int adolescentAge : new int[] {0, 12, 16, 18}) {
                    assertThat(ProxySensitivityPolicy.hiddenFromVerifiedParent(category, age, adolescentAge))
                        .as("%s at %s, adolescent age %d", category, age, adolescentAge)
                        .isEqualTo(!ProxySensitivityPolicy.admits(category, age, adolescentAge, VERIFIED_PARENT));
                }
            }
        }
        assertThat(ProxySensitivityPolicy.hiddenFromVerifiedParent(null, 15, 12)).isFalse();
        // What the reviewer found listed as hidden while the parent already read it.
        assertThat(ProxySensitivityPolicy.hiddenFromVerifiedParent(SensitivityCategory.SICKLE_CELL, 15, 12)).isFalse();
        assertThat(ProxySensitivityPolicy.hiddenFromVerifiedParent(SensitivityCategory.VIRAL_HEPATITIS, 6, 12)).isFalse();
        assertThat(ProxySensitivityPolicy.hiddenFromVerifiedParent(SensitivityCategory.VIRAL_HEPATITIS, 15, 12)).isTrue();
    }

    @Test
    @DisplayName("a child's own sickle status stays visible to the verified parent, at every age under 18")
    void aChildsSickleStatusStaysVisible() {
        assertThat(ProxySensitivityPolicy.admits(SensitivityCategory.SICKLE_CELL, 3, ADOLESCENT, VERIFIED_PARENT)).isTrue();
        assertThat(ProxySensitivityPolicy.admits(SensitivityCategory.SICKLE_CELL, 16, ADOLESCENT, VERIFIED_GUARDIAN)).isTrue();
        // Not to an adult's family, not to an unverified or non-parent grant.
        assertThat(ProxySensitivityPolicy.admits(SensitivityCategory.SICKLE_CELL, 18, ADOLESCENT, VERIFIED_PARENT)).isFalse();
        assertThat(ProxySensitivityPolicy.admits(SensitivityCategory.SICKLE_CELL, 3, ADOLESCENT, DECLARED_PARENT)).isFalse();
        assertThat(ProxySensitivityPolicy.admits(SensitivityCategory.SICKLE_CELL, 3, ADOLESCENT, SPOUSE)).isFalse();
    }

    @Test
    @DisplayName("the other categories follow the hospital's adolescent age: visible under it, hidden from it to 17")
    void theOtherCategoriesFollowTheAdolescentAge() {
        for (SensitivityCategory category : new SensitivityCategory[] {SensitivityCategory.STI,
                SensitivityCategory.VIRAL_HEPATITIS, SensitivityCategory.PREGNANCY,
                SensitivityCategory.REPRODUCTIVE_HEALTH, SensitivityCategory.BEHAVIOURAL_HEALTH,
                SensitivityCategory.SUBSTANCE_USE, SensitivityCategory.GENETIC, SensitivityCategory.TUBERCULOSIS}) {
            assertThat(ProxySensitivityPolicy.admits(category, 11, 12, VERIFIED_PARENT)).as("%s at 11", category).isTrue();
            assertThat(ProxySensitivityPolicy.admits(category, 12, 12, VERIFIED_PARENT)).as("%s at 12", category).isFalse();
            assertThat(ProxySensitivityPolicy.admits(category, 17, 12, VERIFIED_PARENT)).as("%s at 17", category).isFalse();
            // The hospital's own age: 15.
            assertThat(ProxySensitivityPolicy.admits(category, 14, 15, VERIFIED_PARENT)).as("%s at 14/15", category).isTrue();
            // Only a verified parent or guardian.
            assertThat(ProxySensitivityPolicy.admits(category, 5, 12, DECLARED_PARENT)).as("%s unverified", category).isFalse();
            assertThat(ProxySensitivityPolicy.admits(category, 5, 12, SPOUSE)).as("%s not a parent", category).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(SensitivityCategory.class)
    @DisplayName("every category has a rule for a minor: none is left unclassified")
    void everyCategoryIsClassified(SensitivityCategory category) {
        assertThat(ProxySensitivityPolicy.minorRuleOf(category)).isNotNull();
    }

    @Test
    @DisplayName("a release counts for a verified parent of a patient under 18, and ends at 18")
    void aReleaseEndsAtMajority() {
        assertThat(ProxySensitivityPolicy.honoursReleases(17, VERIFIED_PARENT)).isTrue();
        assertThat(ProxySensitivityPolicy.honoursReleases(18, VERIFIED_PARENT)).isFalse();
        assertThat(ProxySensitivityPolicy.honoursReleases(null, VERIFIED_PARENT)).isFalse();
        assertThat(ProxySensitivityPolicy.honoursReleases(10, DECLARED_PARENT)).isFalse();
        assertThat(ProxySensitivityPolicy.honoursReleases(10, SPOUSE)).isFalse();
        assertThat(ProxySensitivityPolicy.honoursReleases(10, null)).isFalse();
    }

    @Test
    @DisplayName("the age is in whole years on the day of the read; the provisional default is 12")
    void ages() {
        LocalDate today = LocalDate.of(2026, 11, 20);
        assertThat(ProxySensitivityPolicy.ageOn(LocalDate.of(2008, 11, 21), today)).isEqualTo(17);
        assertThat(ProxySensitivityPolicy.ageOn(LocalDate.of(2008, 11, 20), today)).isEqualTo(18);
        assertThat(ProxySensitivityPolicy.ageOn(null, today)).isNull();
        assertThat(ProxySensitivityPolicy.ageOn(today.plusDays(1), today)).isNull();
        assertThat(ProxySensitivityPolicy.adolescentAgeOr(null)).isEqualTo(12);
        assertThat(ProxySensitivityPolicy.adolescentAgeOr(14)).isEqualTo(14);
    }

    @Test
    @DisplayName("every category maps to an HL7 v3 sensitivity code, or is restricted (confidentiality R)")
    void hl7Codes() {
        for (SensitivityCategory category : SensitivityCategory.values()) {
            assertThat(category.hl7SensitivityCode() != null || category.restricted())
                .as("%s has an HL7 code or is restricted", category).isTrue();
        }
        assertThat(SensitivityCategory.HIV.hl7SensitivityCode()).isEqualTo("HIV");
        assertThat(SensitivityCategory.STI.hl7SensitivityCode()).isEqualTo("STD");
        assertThat(SensitivityCategory.VIRAL_HEPATITIS.hl7SensitivityCode()).isEqualTo("STD");
        assertThat(SensitivityCategory.PREGNANCY.hl7SensitivityCode()).isEqualTo("PREGNANT");
        assertThat(SensitivityCategory.REPRODUCTIVE_HEALTH.hl7SensitivityCode()).isEqualTo("SEX");
        assertThat(SensitivityCategory.SEXUAL_DOMESTIC_VIOLENCE.hl7SensitivityCode()).isEqualTo("SDV");
        assertThat(SensitivityCategory.BEHAVIOURAL_HEALTH.hl7SensitivityCode()).isEqualTo("MH");
        assertThat(SensitivityCategory.SUBSTANCE_USE.hl7SensitivityCode()).isEqualTo("SUD");
        assertThat(SensitivityCategory.GENETIC.hl7SensitivityCode()).isEqualTo("GDIS");
        assertThat(SensitivityCategory.SICKLE_CELL.hl7SensitivityCode()).isEqualTo("SICKLE");
        assertThat(SensitivityCategory.TUBERCULOSIS.restricted()).isTrue();
        assertThat(SensitivityCategory.TUBERCULOSIS.hl7SensitivityCode()).isNull();
        // The column that stores the name is VARCHAR(32).
        for (SensitivityCategory category : SensitivityCategory.values()) {
            assertThat(category.name().length()).as(category.name()).isLessThanOrEqualTo(32);
        }
    }
}
