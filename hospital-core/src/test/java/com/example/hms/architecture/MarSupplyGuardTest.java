package com.example.hms.architecture;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.MedicationAdministrationRecordRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.service.emar.MarOrderStanding;
import com.example.hms.service.emar.MarWardReader;
import com.example.hms.service.pharmacy.PrescriptionControlledResolver;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D3941 (r77): a ward order left the nurse's eMAR as soon as the pharmacy supplied it, in full or in
 * part (3 of 9), before a single dose was given: the MAR read SIGNED and TRANSMITTED orders only, so the
 * pharmacy's DISPENSED and PARTIALLY_FILLED took it off the list. Supplying an order is not giving it.
 * An order stays on the MAR while it stands ({@link MarOrderStanding#stands}: signed, not withdrawn) until
 * the prescriber stops it, its course ends or its stay ends; what the pharmacy supplied is said on the row
 * ({@code MarSupply}), never used to filter it.
 *
 * <p>This fails when a MAR read narrows by a pharmacy supply status again: the statuses the MAR lists by,
 * the statuses its ward query is given, the query's own text, and every MAR read of the nurse service.
 */
@DisplayName("D3941: no eMAR read filters on what the pharmacy supplied")
class MarSupplyGuardTest {

    private static final Path ROOT = Path.of("src/main/java/com/example/hms");

    /** The statuses the pharmacy writes when it supplies, routes or prints an order. */
    private static final Set<PrescriptionStatus> SUPPLY_STATUSES = EnumSet.of(
        PrescriptionStatus.TRANSMITTED, PrescriptionStatus.TRANSMISSION_FAILED, PrescriptionStatus.PENDING_CLARIFICATION,
        PrescriptionStatus.DISPENSED, PrescriptionStatus.PARTIALLY_FILLED, PrescriptionStatus.PENDING_STOCK,
        PrescriptionStatus.SENT_TO_PARTNER, PrescriptionStatus.PARTNER_ACCEPTED, PrescriptionStatus.PARTNER_REJECTED,
        PrescriptionStatus.PARTNER_DISPENSED, PrescriptionStatus.PRINTED_FOR_PATIENT);

    /** A supply status (or the dispense table's status) named in code. */
    private static final Pattern SUPPLY_TOKEN = Pattern.compile("\\b(?:"
        + SUPPLY_STATUSES.stream().filter(s -> s != PrescriptionStatus.TRANSMITTED).map(Enum::name)
            .collect(Collectors.joining("|"))
        + "|DispenseStatus)\\b");

    /** The MAR reads of the nurse service: the list, the tiles, the workboard, and the rule they share. */
    private static final List<String> NURSE_MAR_METHODS = List.of("getMedicationTasks", "readWardOrders",
        "buildMedicationTasksForPatient", "countMedicationStatuses", "countedMarStatus", "onWardMar",
        "getWorkboard", "toWorkboardCard", "orderOfCurrentStay", "givenDuringOpenEmergencyVisit");

    @Test
    @DisplayName("the MAR lists every order that stands, whatever the pharmacy supplied")
    void theMarStatusesAreEveryStandingStatus() {
        Set<PrescriptionStatus> standing = Arrays.stream(PrescriptionStatus.values())
            .filter(MarOrderStanding::stands).collect(Collectors.toCollection(() -> EnumSet.noneOf(PrescriptionStatus.class)));
        assertThat(MarWardReader.ON_MAR_STATUSES).as("the list and the dose share one rule" + SourceScan.MANDATE)
            .containsExactlyInAnyOrderElementsOf(standing);
        assertThat(MarWardReader.ON_MAR_STATUSES).as("a supplied order is still to be given" + SourceScan.MANDATE)
            .containsAll(SUPPLY_STATUSES);
        assertThat(MarWardReader.ON_MAR_STATUSES).doesNotContain(PrescriptionStatus.DRAFT,
            PrescriptionStatus.PENDING_SIGNATURE, PrescriptionStatus.CANCELLED, PrescriptionStatus.DISCONTINUED);
    }

    @Test
    @DisplayName("the ward query is asked for every standing status, supplied ones included")
    @SuppressWarnings("unchecked")
    void theWardQueryIsGivenTheSupplyStatuses() {
        PrescriptionRepository prescriptions = mock(PrescriptionRepository.class);
        MarWardReader reader = new MarWardReader(prescriptions, mock(AdmissionRepository.class),
            mock(MedicationAdministrationRecordRepository.class), mock(PatientAllergyRepository.class),
            mock(PatientVitalSignRepository.class), mock(PrescriptionControlledResolver.class), mock(DispenseRepository.class));
        UUID hospital = UUID.randomUUID();
        when(prescriptions.findWardMarOrders(eq(hospital), any(), any(), any())).thenReturn(List.of());

        reader.ordersByPatient(hospital, List.of(UUID.randomUUID()));

        ArgumentCaptor<Collection<PrescriptionStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(prescriptions).findWardMarOrders(eq(hospital), statuses.capture(), eq(EncounterType.EMERGENCY),
            eq(EncounterStatus.CLOSED));
        assertThat(statuses.getValue()).as(SourceScan.MANDATE).containsAll(SUPPLY_STATUSES);
    }

    @Test
    @DisplayName("no MAR read names a supply status: the ward query, the reader, the nurse service's MAR reads")
    void noMarReadNamesASupplyStatus() throws IOException {
        List<String> problems = new ArrayList<>();

        String repository = SourceScan.stripComments(read("repository/PrescriptionRepository.java"));
        String query = queryOf(repository, "findWardMarOrders");
        assertThat(query).as("findWardMarOrders keeps its @Query").isNotNull();
        if (SUPPLY_TOKEN.matcher(query).find() || query.contains("Dispense")) {
            problems.add("PrescriptionRepository.findWardMarOrders filters on a supply status or a dispense: " + query);
        }
        if (!query.contains("p.status IN :orderStatuses")) {
            problems.add("PrescriptionRepository.findWardMarOrders no longer takes its statuses from MarWardReader.ON_MAR_STATUSES");
        }

        String reader = SourceScan.stripComments(read("service/emar/MarWardReader.java"));
        String ordersByPatient = SourceScan.methodBody(reader, "ordersByPatient");
        assertThat(ordersByPatient).as("MarWardReader.ordersByPatient").isNotNull();
        if (!ordersByPatient.contains("ON_MAR_STATUSES")) {
            problems.add("MarWardReader.ordersByPatient no longer asks for ON_MAR_STATUSES");
        }
        findSupplyTokens("MarWardReader", reader, problems);

        String nurse = SourceScan.stripComments(read("service/impl/NurseTaskServiceImpl.java"));
        if (!Pattern.compile("ACTIVE_RX_STATUSES\\s*+=\\s*+(?:[\\w.]+\\.)?MarWardReader\\.ON_MAR_STATUSES\\s*+;").matcher(nurse).find()) {
            problems.add("NurseTaskServiceImpl.ACTIVE_RX_STATUSES is not MarWardReader.ON_MAR_STATUSES: a status list of its own");
        }
        for (String method : NURSE_MAR_METHODS) {
            String body = SourceScan.methodBody(nurse, method);
            if (body == null) {
                problems.add("NurseTaskServiceImpl." + method + " is gone: update this guard's list of MAR reads");
                continue;
            }
            findSupplyTokens("NurseTaskServiceImpl." + method, body, problems);
            if (body.contains("dispense") || body.contains("Dispense")) {
                problems.add("NurseTaskServiceImpl." + method + " reads a dispense: supply is information on the row only");
            }
        }
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
    }

    private static void findSupplyTokens(String where, String code, List<String> problems) {
        Matcher m = SUPPLY_TOKEN.matcher(code);
        while (m.find()) {
            problems.add(where + " names " + m.group() + ": the MAR must not filter on what the pharmacy supplied (MarSupply says it on the row)");
        }
    }

    /** The text of the {@code @Query} on the repository method {@code name}, or null. */
    private static String queryOf(String code, String name) {
        int method = code.indexOf(" " + name + "(");
        if (method < 0) {
            return null;
        }
        int query = code.lastIndexOf("@Query(", method);
        return query < 0 ? null : code.substring(query, method);
    }

    private static String read(String rel) throws IOException {
        return Files.readString(ROOT.resolve(rel), StandardCharsets.UTF_8);
    }
}
