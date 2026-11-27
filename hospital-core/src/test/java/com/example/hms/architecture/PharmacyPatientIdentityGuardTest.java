package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4185 (r81): the dispensing queue and the dispense panel named the patient and nothing else, so two patients
 * with the same name could not be told apart at the counter. The class: a pharmacy response that identifies a
 * patient by name only.
 *
 * <p>Every DTO under {@code payload/dto/pharmacy} that carries a patient's name also carries the record number
 * (and, where a person is picked from a list, the date of birth), or is listed below with its defect id.
 */
@DisplayName("QA D4185: a pharmacy row that names a patient carries the record number")
class PharmacyPatientIdentityGuardTest {

    private static final Path DTOS = Path.of("src/main/java/com/example/hms/payload/dto/pharmacy");

    /**
     * Known rows that still name the patient alone, each with why it is not fixed here. Their screens belong to
     * the stock lane of r81 (branch fix/r81-pharmacy-stock); they are standing debt of D4185, not exemptions.
     */
    private static final Map<String, String> STANDING_DEBT = Map.of(
        "ControlledRegisterEntryDTO.java",
        "D4185: the controlled-drug register row (a legal register, exported; its layout is the stock lane's)",
        "ReturnableDispenseDTO.java",
        "D4185: the return picker of the stock-adjustment screen (stock lane)");

    @Test
    @DisplayName("no pharmacy DTO names a patient without the record number")
    void everyPatientNameComesWithARecordNumber() throws IOException {
        List<String> nameOnly = new ArrayList<>();
        try (var paths = Files.list(DTOS)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String source = read(path);
                boolean namesAPatient = source.contains("String patientName")
                    || source.contains("class Patient {");
                if (!namesAPatient || STANDING_DEBT.containsKey(path.getFileName().toString())) {
                    continue;
                }
                boolean recordNumber = source.contains("String patientMrn") || source.contains("String mrn;");
                if (!recordNumber) {
                    nameOnly.add(path.getFileName().toString());
                }
            }
        }
        assertThat(nameOnly)
            .as("a pharmacy DTO with a patient name must carry the record number at the acting hospital "
                + "(PatientIdentityLine.mrnOf)")
            .isEmpty();
    }

    @Test
    @DisplayName("the queue row, the fill and the claim read the record number and the date of birth in one place")
    void theIdentityLineIsReadInOnePlace() throws IOException {
        Path main = Path.of("src/main/java/com/example/hms");
        assertThat(read(main.resolve("service/pharmacy/DispenseServiceImpl.java")))
            .as("the dispensing queue row")
            .contains(".mrn(com.example.hms.mapper.pharmacy.PatientIdentityLine.mrnOf(p.getPatient(),")
            .contains(".dateOfBirth(com.example.hms.mapper.pharmacy.PatientIdentityLine.dateOfBirthOf(p.getPatient()))");
        for (String mapper : List.of("DispenseMapper.java", "PharmacyClaimMapper.java")) {
            assertThat(read(main.resolve("mapper/pharmacy/" + mapper))).as(mapper)
                .contains(".patientMrn(PatientIdentityLine.mrnOf(")
                .contains(".patientDateOfBirth(PatientIdentityLine.dateOfBirthOf(");
        }
        assertThat(read(main.resolve("mapper/pharmacy/PatientIdentityLine.java")))
            .as("the record number is the acting hospital's, never another hospital's")
            .contains("patient.getMrnForHospital(hospitalId)");
    }

    @Test
    @DisplayName("the standing-debt list holds only DTOs that still exist and still name the patient alone")
    void theDebtListIsHonest() throws IOException {
        for (String file : STANDING_DEBT.keySet()) {
            String source = read(DTOS.resolve(file));
            assertThat(source).as(file + " still names a patient").contains("String patientName");
            assertThat(source.contains("String patientMrn"))
                .as(file + " now carries the record number: remove it from STANDING_DEBT").isFalse();
        }
    }

    /** The file's text with Unix line ends, whatever the checkout wrote. */
    private static String read(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n");
    }
}
