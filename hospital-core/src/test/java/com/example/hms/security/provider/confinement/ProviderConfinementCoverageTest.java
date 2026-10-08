package com.example.hms.security.provider.confinement;

import com.example.hms.enums.FacilityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes what a provider user reaches (provider plan §6.4, T19). Every MVC
 * handler of the application is put to the same decision the confinement
 * filter makes at runtime ({@link ProviderConfinement#allows}); the handlers
 * it lets through must equal the frozen snapshots under
 * {@code src/test/resources/provider-confinement/}:
 *
 * <ul>
 *   <li>{@code common.txt}: any provider (the wholesale prefixes, expanded
 *       handler by handler, and the exact entries);</li>
 *   <li>{@code pharmacy.txt} / {@code laboratory.txt}: what that type adds
 *       (P2-PH and P2-LAB own them);</li>
 *   <li>{@code patient-self-service.txt}: what a provider user who also holds
 *       a PATIENT assignment adds.</li>
 * </ul>
 *
 * <p>A new handler under {@code /auth}, {@code /notifications} or
 * {@code /me/patient}, or a new entry, fails here until the snapshot is
 * updated deliberately, with the reason in the allow-list file. An entry
 * naming no handler fails too: a dangling entry would open whatever is mapped
 * there next.
 */
class ProviderConfinementCoverageTest {

    private static final Set<FacilityType> PHARMACY = EnumSet.of(FacilityType.PHARMACY);
    private static final Set<FacilityType> LABORATORY = EnumSet.of(FacilityType.LABORATORY);

    @Test
    @DisplayName("what any provider reaches equals provider-confinement/common.txt")
    void commonSnapshot() {
        assertThat(reachable(HandlerInventory.handlers(), PHARMACY, false))
            .as("handlers any provider reaches; update common.txt deliberately")
            .containsExactlyElementsOf(snapshot("common.txt"));
        assertThat(reachable(HandlerInventory.handlers(), LABORATORY, false))
            .containsExactlyElementsOf(snapshot("common.txt"));
    }

    @Test
    @DisplayName("what a pharmacy and a laboratory add equals pharmacy.txt and laboratory.txt")
    void facilitySnapshots() {
        List<HandlerInventory.Handler> handlers = HandlerInventory.handlers();
        TreeSet<String> common = reachable(handlers, PHARMACY, false);
        common.retainAll(reachable(handlers, LABORATORY, false));

        TreeSet<String> pharmacy = reachable(handlers, PHARMACY, false);
        pharmacy.removeAll(common);
        assertThat(pharmacy).containsExactlyElementsOf(snapshot("pharmacy.txt"));

        TreeSet<String> laboratory = reachable(handlers, LABORATORY, false);
        laboratory.removeAll(common);
        assertThat(laboratory).containsExactlyElementsOf(snapshot("laboratory.txt"));
    }

    @Test
    @DisplayName("what a provider user who is also a patient adds equals patient-self-service.txt")
    void patientSelfServiceSnapshot() {
        List<HandlerInventory.Handler> handlers = HandlerInventory.handlers();
        TreeSet<String> added = reachable(handlers, PHARMACY, true);
        added.removeAll(reachable(handlers, PHARMACY, false));
        assertThat(added)
            .as("patient self-service a provider user keeps; update patient-self-service.txt deliberately")
            .containsExactlyElementsOf(snapshot("patient-self-service.txt"));
    }

    @Test
    @DisplayName("every exact entry names a real handler, and has a reason")
    void noDanglingEntry() {
        TreeSet<String> mapped = HandlerInventory.keys(HandlerInventory.handlers());
        List<ConfinementRule> entries = new ArrayList<>();
        entries.addAll(CommonProviderConfinement.RULES);
        entries.addAll(CommonProviderConfinement.PATIENT_SELF_SERVICE_RULES);
        entries.addAll(PharmacyConfinement.RULES);
        entries.addAll(LaboratoryConfinement.RULES);
        for (ConfinementRule entry : entries) {
            assertThat(mapped).as("allow-list entry with no handler").contains(entry.method() + " " + entry.pattern());
            assertThat(entry.reason()).as("%s %s", entry.method(), entry.pattern()).isNotBlank();
        }
        for (String prefix : CommonProviderConfinement.WHOLESALE_PREFIXES) {
            assertThat(mapped).as("wholesale prefix %s covers nothing", prefix)
                .anyMatch(key -> ProviderConfinement.underAny(key.substring(key.indexOf(' ') + 1), List.of(prefix)));
        }
    }

    @Test
    @DisplayName("a new handler under a wholesale prefix changes the reachable set, so the snapshot catches it")
    void newHandlerUnderAWholesalePrefixIsCaught() {
        List<HandlerInventory.Handler> handlers = new ArrayList<>(HandlerInventory.handlers());
        handlers.add(new HandlerInventory.Handler("DELETE", "/auth/everything", "Synthetic.handler"));
        handlers.add(new HandlerInventory.Handler("GET", "/notifications/broadcast-to-hospital", "Synthetic.handler"));
        assertThat(reachable(handlers, PHARMACY, false))
            .contains("DELETE /auth/everything", "GET /notifications/broadcast-to-hospital")
            .isNotEqualTo(snapshot("common.txt"));
    }

    @Test
    @DisplayName("the prefixes are path segments: /authority or /notificationsx is not under /auth or /notifications")
    void prefixesAreSegments() {
        assertThat(ProviderConfinement.allows(PHARMACY, false, "GET", "/authority/x")).isFalse();
        assertThat(ProviderConfinement.allows(PHARMACY, false, "GET", "/notificationsx")).isFalse();
        assertThat(ProviderConfinement.allows(PHARMACY, false, "GET", "/me/patients/{patientId}/snapshot")).isFalse();
        assertThat(ProviderConfinement.allows(PHARMACY, true, "GET", "/me/patients/{patientId}/snapshot")).isFalse();
        assertThat(ProviderConfinement.allows(PHARMACY, true, "GET", "/me/patient/profile")).isTrue();
        assertThat(ProviderConfinement.allows(PHARMACY, false, "GET", "/me/patient/profile")).isFalse();
    }

    @Test
    @DisplayName("exact entries match method and pattern, never a prefix; HEAD counts as GET")
    void exactEntriesAreMethodAndPattern() {
        assertThat(ProviderConfinement.allows(PHARMACY, false, "POST", "/users/admin-register")).isTrue();
        assertThat(ProviderConfinement.allows(PHARMACY, false, "GET", "/users/admin-register")).isFalse();
        assertThat(ProviderConfinement.allows(PHARMACY, false, "POST", "/users/admin-register/x")).isFalse();
        assertThat(ProviderConfinement.allows(PHARMACY, false, "HEAD", "/notifications")).isTrue();
        assertThat(ProviderConfinement.allows(PHARMACY, false, "GET", null)).isFalse();
        assertThat(ProviderConfinement.allowsNonMvc("GET", "/actuator/health")).isTrue();
        assertThat(ProviderConfinement.allowsNonMvc("GET", "/actuator/metrics")).isFalse();
        assertThat(ProviderConfinement.allowsNonMvc("POST", "/actuator/health")).isFalse();
    }

    @Test
    @DisplayName("a facility handler needs every provider type the caller holds (narrowest, never the union)")
    void severalTypesGetTheNarrowerSet() {
        assertThat(ProviderConfinement.allows(EnumSet.of(FacilityType.PHARMACY, FacilityType.LABORATORY),
            false, "GET", "/lab-orders")).isFalse();
        assertThat(ProviderConfinement.allows(EnumSet.noneOf(FacilityType.class), false, "GET", "/lab-orders"))
            .isFalse();
        assertThat(ProviderConfinement.rulesFor(FacilityType.HOSPITAL)).isEmpty();
    }

    private static TreeSet<String> reachable(List<HandlerInventory.Handler> handlers,
                                             Set<FacilityType> types, boolean patientHolder) {
        TreeSet<String> reachable = new TreeSet<>();
        for (HandlerInventory.Handler handler : handlers) {
            if (ProviderConfinement.allows(types, patientHolder, handler.method(), handler.pattern())) {
                reachable.add(handler.key());
            }
        }
        return reachable;
    }

    private static TreeSet<String> snapshot(String file) {
        String resource = "/provider-confinement/" + file;
        try (InputStream in = ProviderConfinementCoverageTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("missing snapshot %s", resource).isNotNull();
            TreeSet<String> lines = new TreeSet<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
            return lines;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
