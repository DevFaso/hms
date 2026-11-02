package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QA D1276 (round 27, class guard): the refill queue listed refill requests of
 * many patients and sent only {@code patientId}, so three identical
 * "Paracétamol" cards could not be told apart. Class: a response DTO that
 * names a patient by id only. A list that mixes patients must carry the
 * patient's display name.
 *
 * <p>Every response DTO with a {@code patientId} and no patient name field is
 * listed below with the reason it is not a mixed-patient list (every endpoint
 * that returns it is keyed on one patient, or it describes the caller), or as
 * known debt with its defect id. A new DTO, or a DTO that loses its name
 * field, fails here until it is named.
 */
class PatientQueueDisplayNameGuardTest {

    private static final Path PAYLOAD = Paths.get("src/main/java/com/example/hms/payload");
    private static final Pattern PATIENT_ID = Pattern.compile("private\\s++UUID\\s++patientId\\s*+;");
    private static final Pattern PATIENT_NAME = Pattern.compile(
        "private\\s++String\\s++patient(?:Full|Display)?Name\\s*+;");

    private static final String CHART = "chart-scoped: every endpoint returning it takes the patient (path or "
        + "required parameter), so the reader already knows who it is";
    private static final String OWN = "the caller's own record (patient portal or the signed-in account)";

    private static final Map<String, String> ID_ONLY = Map.ofEntries(
        Map.entry("dto/AdvanceDirectiveResponseDTO.java", CHART),
        Map.entry("dto/cds/CdsAcknowledgementResponseDTO.java", CHART + " (GET /cds-acknowledgements?patientId=)"),
        Map.entry("dto/clinical/labor/DeliveryRecordResponseDTO.java", CHART),
        Map.entry("dto/clinical/labor/PartographEntryResponseDTO.java", CHART),
        Map.entry("dto/clinical/MaternalHistoryResponseDTO.java", CHART),
        Map.entry("dto/clinical/newborn/NewbornAssessmentResponseDTO.java", CHART),
        Map.entry("dto/clinical/postpartum/PostpartumObservationResponseDTO.java", CHART),
        Map.entry("dto/education/PatientEducationProgressResponseDTO.java", OWN),
        Map.entry("dto/education/PatientEducationQuestionResponseDTO.java", OWN),
        Map.entry("dto/GuarantorResponseDTO.java", CHART),
        Map.entry("dto/insurance/EligibilityResponseDTO.java", CHART + " (listByPatient)"),
        Map.entry("dto/JwtResponse.java", OWN),
        Map.entry("dto/LabSpecimenResponseDTO.java", "listed by lab order, which names the patient"),
        Map.entry("dto/PatientAllergyResponseDTO.java", CHART),
        Map.entry("dto/PatientChartUpdateResponseDTO.java", CHART),
        Map.entry("dto/PatientConsentResponseDTO.java", CHART),
        Map.entry("dto/PatientHospitalRegistrationResponseDTO.java", CHART),
        Map.entry("dto/PatientInsuranceResponseDTO.java", CHART),
        Map.entry("dto/PatientPrimaryCareResponseDTO.java", CHART),
        Map.entry("dto/PatientProblemResponseDTO.java", CHART),
        Map.entry("dto/PatientSurgicalHistoryResponseDTO.java", CHART),
        Map.entry("dto/PatientVitalSignResponseDTO.java", CHART),
        Map.entry("dto/pharmacy/PharmacyPaymentResponseDTO.java", "listed by dispense or by patient, or the "
            + "patient's own payments"),
        Map.entry("dto/pharmacy/PharmacySaleResponseDTO.java", "D1276 debt: GET /pharmacy-sales/hospital/{id} and "
            + "/pharmacy/{id} mix patients, but no screen lists them yet (no portal caller); add the name before "
            + "one does"),
        Map.entry("dto/pharmacy/RoutingDecisionResponseDTO.java", "listed by prescription or by patient"),
        Map.entry("dto/portal/PatientDocumentResponseDTO.java", CHART + ", or the patient's own documents"),
        Map.entry("dto/prenatal/PrenatalScheduleResponseDTO.java", CHART),
        Map.entry("dto/pro/ProResponseDTO.java", CHART + " (/patients/{patientId}/pro)"),
        Map.entry("dto/SessionBootstrapResponseDTO.java", OWN),
        Map.entry("dto/TreatmentConsentResponseDTO.java", CHART),
        Map.entry("dto/UserResponseDTO.java", OWN));

    @Test
    void everyResponseNamingAPatientByIdCarriesTheirNameOrIsListedWithAReason() throws IOException {
        TreeSet<String> idOnly = new TreeSet<>();
        try (Stream<Path> files = Files.walk(PAYLOAD)) {
            for (Path f : files.filter(p -> isResponseSource(p.getFileName().toString())).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                if (PATIENT_ID.matcher(src).find() && !PATIENT_NAME.matcher(src).find()) {
                    idOnly.add(PAYLOAD.relativize(f).toString().replace('\\', '/'));
                }
            }
        }
        List<String> unexplained = new ArrayList<>(idOnly);
        unexplained.removeAll(ID_ONLY.keySet());
        assertThat(unexplained).as("a response naming a patient by id only (QA D1276): add the patient's name, "
            + "or list it here with the reason no list mixes patients").isEmpty();
        List<String> stale = new ArrayList<>(ID_ONLY.keySet());
        stale.removeAll(idOnly);
        assertThat(stale).as("allowlist entries that now carry a name or are gone: remove them").isEmpty();
        assertThat(idOnly).as("the refill queue's DTO (QA D1276)")
            .doesNotContain("dto/portal/MedicationRefillResponseDTO.java");
    }

    /** A response DTO source file (a plain name check: Sonar S8786 flagged the ".*Response.*" regex). */
    private static boolean isResponseSource(String fileName) {
        return fileName.contains("Response") && fileName.endsWith(".java");
    }
}
