package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4141 (r81): a « second person » a request names is checked by the server, never taken on the
 * client's word.
 *
 * <p>The class this guards: a request names a colleague as the second person of a two-person act (a
 * witness, a second checker, an authorising physician), the portal's picker lists only the right
 * people, and the server accepts whoever the request names. Any other client then records the act
 * with a person who could never have made the check.
 *
 * <p>Every such field of every request DTO is listed here with the class that decides who may be
 * named and the call that asks it. A new field fails this test until it is listed with its check, or
 * with the reason it is not a second person.
 */
@DisplayName("QA D4141: every second person a request names is role-checked on the server")
class SecondPersonCheckGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path DTOS = MAIN.resolve("payload/dto");

    /** A UUID field of a request that names somebody else for a two-person act. */
    private static final Pattern SECOND_PERSON_FIELD = Pattern.compile(
        "private\\s+(?:java\\.util\\.)?UUID\\s+(\\w*(?:[Ww]itness|verifiedBy|[Vv]erifier|[Aa]uthoriser|[Aa]uthorizer"
            + "|[Bb]iologist|orderingPhysician|[Cc]ountersign|[Cc]osign|secondCheck)\\w*)\\s*;");

    /** The check of a named second person: the class that decides, and the call that asks the role. */
    private record Check(String decidedIn, String asks) { }

    private static final Check CONTROLLED_DOSE = new Check("service/emar/ControlledAdministrationWitness.java",
        "existsActiveWitness(witnessUserId, hospitalId, WITNESS_ROLE_CODES)");
    private static final Check CONTROLLED_STOCK = new Check("service/pharmacy/ControlledStockWitness.java",
        "existsActiveWitness(");
    private static final Check BLOOD_RELEASE = new Check("service/transfusion/OPositiveReleaseAuthorityService.java",
        "ROLE_CODES");
    private static final Check BEDSIDE_CHECK = new Check("service/impl/TransfusionServiceImpl.java",
        "BedsideVerifierConfirmation.holdsBedsideRole(verifier)");

    private static final Map<String, Check> CHECKED = Map.ofEntries(
        Map.entry("nurse/NurseMedicationAdministrationRequestDTO.witnessUserId", CONTROLLED_DOSE),
        Map.entry("pharmacy/CancelReadyRequestDTO.witnessedBy", CONTROLLED_STOCK),
        Map.entry("pharmacy/DispenseCancelRequestDTO.witnessedBy", CONTROLLED_STOCK),
        Map.entry("pharmacy/DispenseRequestDTO.witnessedBy", CONTROLLED_STOCK),
        Map.entry("pharmacy/QuarantineDestructionRequestDTO.witnessedBy", CONTROLLED_STOCK),
        Map.entry("pharmacy/StockLotRequestDTO.witnessedBy", CONTROLLED_STOCK),
        Map.entry("pharmacy/StockTransactionRequestDTO.witnessedBy", CONTROLLED_STOCK),
        Map.entry("pharmacy/WitnessConfirmationRequestDTO.witnessedBy", CONTROLLED_STOCK),
        Map.entry("transfusion/EmergencyReleaseRequestDTO.authoriserUserId", BLOOD_RELEASE),
        Map.entry("transfusion/EmergencyReleaseRequestDTO.biologistUserId", BLOOD_RELEASE),
        Map.entry("transfusion/EmergencyReleaseRequestDTO.orderingPhysicianUserId", BLOOD_RELEASE),
        Map.entry("transfusion/TransfusionAdministrationRequestDTO.verifiedByStaffId", BEDSIDE_CHECK),
        Map.entry("transfusion/TransfusionAdministrationRequestDTO.verifiedByUserId", BEDSIDE_CHECK));

    /** Fields the pattern finds that name nobody else, with the reason. */
    private static final Map<String, String> NOT_A_SECOND_PERSON = Map.of(
        "pharmacy/DispenseRequestDTO.verifiedBy",
        "the pharmacist's own verification: DispenseServiceImpl refuses any id but the session user's");

    @Test
    void everySecondPersonFieldIsListedWithItsServerCheck() throws IOException {
        List<String> found = secondPersonFields();
        assertThat(found).as("the scan finds the fields it is about").hasSizeGreaterThanOrEqualTo(CHECKED.size());

        List<String> unlisted = found.stream()
            .filter(field -> !CHECKED.containsKey(field) && !NOT_A_SECOND_PERSON.containsKey(field))
            .toList();
        assertThat(unlisted)
            .as("a request field that names a second person, with no server-side check listed for it")
            .isEmpty();

        List<String> gone = new ArrayList<>();
        new TreeSet<>(CHECKED.keySet()).stream().filter(field -> !found.contains(field)).forEach(gone::add);
        new TreeSet<>(NOT_A_SECOND_PERSON.keySet()).stream().filter(field -> !found.contains(field)).forEach(gone::add);
        assertThat(gone).as("listed here but no longer a field of a request: remove the entry").isEmpty();
    }

    @Test
    void theClassThatDecidesAsksTheRole() throws IOException {
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, Check> entry : CHECKED.entrySet()) {
            Path decidedIn = MAIN.resolve(entry.getValue().decidedIn());
            if (!Files.exists(decidedIn)) {
                broken.add(entry.getKey() + ": " + entry.getValue().decidedIn() + " does not exist");
                continue;
            }
            String source = Files.readString(decidedIn, StandardCharsets.UTF_8);
            if (!source.contains(entry.getValue().asks())) {
                broken.add(entry.getKey() + ": " + entry.getValue().decidedIn() + " no longer calls "
                    + entry.getValue().asks());
            }
        }
        assertThat(broken).as("a named second person whose role is no longer asked on the server").isEmpty();
    }

    @Test
    @DisplayName("the bedside check: the role, then the checker's own confirmation, then the hang")
    void theBedsideCheckIsRoleCheckedAndConfirmedBeforeTheBagIsHung() throws IOException {
        String source = Files.readString(MAIN.resolve("service/impl/TransfusionServiceImpl.java"), StandardCharsets.UTF_8);
        int start = source.indexOf("public TransfusionAdministrationResponseDTO startAdministration(");
        assertThat(start).as("startAdministration").isPositive();
        String body = source.substring(start, source.indexOf("\n    }\n", start) < 0
            ? source.indexOf("\r\n    }\r\n", start) : source.indexOf("\n    }\n", start));

        int role = body.indexOf("BedsideVerifierConfirmation.holdsBedsideRole(verifier)");
        int confirmation = body.indexOf("bedsideVerifier.confirm(");
        int hang = body.indexOf("administrationRepository.save(");
        assertThat(role).as("the role of the named checker is asked").isPositive();
        assertThat(confirmation).as("the checker confirms themselves, after the role is asked").isGreaterThan(role);
        assertThat(hang).as("nothing is hung before the confirmation").isGreaterThan(confirmation);

        // The picker and the hang ask the same question, so they cannot drift apart.
        int picker = source.indexOf("listVerifierCandidates()");
        assertThat(source.indexOf("BedsideVerifierConfirmation::holdsBedsideRole", picker))
            .as("the picker lists the people the hang accepts").isPositive();
    }

    private static List<String> secondPersonFields() throws IOException {
        List<String> fields = new ArrayList<>();
        try (Stream<Path> files = Files.walk(DTOS)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith("RequestDTO.java")).toList()) {
                String name = DTOS.relativize(file).toString().replace('\\', '/');
                name = name.substring(0, name.length() - ".java".length());
                Matcher matcher = SECOND_PERSON_FIELD.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    fields.add(name + "." + matcher.group(1));
                }
            }
        }
        return fields.stream().sorted().toList();
    }
}
