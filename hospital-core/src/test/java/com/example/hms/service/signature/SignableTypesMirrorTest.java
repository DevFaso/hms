package com.example.hms.service.signature;

import com.example.hms.enums.SignatureType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * QA D627: the sign modal offered OPERATIVE_NOTE, PATHOLOGY_REPORT, CARE_PLAN
 * and GENERIC_REPORT, which {@link SignableReportLocator} never resolves, so
 * every signature over them ended in a 404. The portal list and the server's
 * SIGNABLE set must name the same types, and SIGNABLE must be exactly the
 * types the locator resolves.
 */
class SignableTypesMirrorTest {

    private static final Path PORTAL = Path.of("..", "hospital-portal", "src", "app", "services",
        "digital-signature.service.ts");

    @Test
    void portalSignModalOffersExactlyTheSignableTypes() throws Exception {
        String source = Files.readString(PORTAL, StandardCharsets.UTF_8);
        Matcher block = Pattern.compile("export const SIGNABLE_TYPES[^=]*=\\s*\\[(.*?)\\];", Pattern.DOTALL)
            .matcher(source);
        assertThat(block.find()).as("SIGNABLE_TYPES in %s", PORTAL.toAbsolutePath()).isTrue();
        Set<String> portal = Pattern.compile("'([A-Z_]+)'").matcher(block.group(1)).results()
            .map(m -> m.group(1)).collect(Collectors.toSet());
        Set<String> server = SignableReportLocator.SIGNABLE.stream().map(Enum::name).collect(Collectors.toSet());
        assertThat(portal).isEqualTo(server);
    }

    @Test
    void signableIsExactlyWhatTheLocatorResolves() {
        // With every repository mocked, an unresolvable type short-circuits to
        // empty without touching a repository; a resolvable one queries its
        // repository (a mock answers Optional.empty() too, so we tell them
        // apart by whether any repository was called).
        for (SignatureType type : SignatureType.values()) {
            var repos = new Object[] {
                mock(com.example.hms.repository.DischargeSummaryRepository.class),
                mock(com.example.hms.repository.LabResultRepository.class),
                mock(com.example.hms.repository.ImagingReportRepository.class),
                mock(com.example.hms.repository.ConsultationRepository.class),
                mock(com.example.hms.repository.EncounterNoteRepository.class),
                mock(com.example.hms.repository.PrescriptionRepository.class),
                mock(com.example.hms.repository.ProcedureOrderRepository.class),
            };
            SignableReportLocator locator = new SignableReportLocator(
                (com.example.hms.repository.DischargeSummaryRepository) repos[0],
                (com.example.hms.repository.LabResultRepository) repos[1],
                (com.example.hms.repository.ImagingReportRepository) repos[2],
                (com.example.hms.repository.ConsultationRepository) repos[3],
                (com.example.hms.repository.EncounterNoteRepository) repos[4],
                (com.example.hms.repository.PrescriptionRepository) repos[5],
                (com.example.hms.repository.ProcedureOrderRepository) repos[6]);
            locator.owningHospitalId(type, UUID.randomUUID());
            boolean queried = false;
            for (Object repo : repos) {
                queried |= !org.mockito.Mockito.mockingDetails(repo).getInvocations().isEmpty();
            }
            assertThat(queried).as("%s resolvable", type).isEqualTo(SignableReportLocator.SIGNABLE.contains(type));
        }
        assertThat(EnumSet.complementOf(EnumSet.copyOf(SignableReportLocator.SIGNABLE)))
            .containsExactlyInAnyOrder(SignatureType.OPERATIVE_NOTE, SignatureType.PATHOLOGY_REPORT,
                SignatureType.CARE_PLAN, SignatureType.GENERIC_REPORT);
    }
}
