package com.example.hms.architecture;

import com.example.hms.payload.dto.notification.PatientNotificationDeliveryDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA r73 D3700 (class): consent choices are stored per patient account, so every hospital the patient is registered
 * at reads them. The recorder's name and account id were shown to all of them, and staff account ids reached the
 * patient. The recorder may reach a reader only through {@code ConsentRecorderLabels}:
 * <ul>
 *   <li>the consent DTOs carry no recorder account id at all;</li>
 *   <li>a recorder name or kind is set on them in {@code ConsentRecorderLabels} and nowhere else, and every file that
 *       builds one of them labels it through {@code labels.apply(...)};</li>
 *   <li>a staff surface labels with the hospital-scoped {@code forStaffViewer}, which reads names only with the
 *       hospital-scoped query; only the account owner's own view may use {@code forAccountOwner}.</li>
 * </ul>
 */
class ConsentRecorderScopeGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    /** Carriage return, stripped so source scans see the same text on Windows checkouts. */
    private static final String CR = String.valueOf((char) 13);
    private static final Path LABELS =
        MAIN.resolve("com/example/hms/service/notification/ConsentRecorderLabels.java");
    private static final List<Class<?>> CONSENT_DTOS =
        List.of(NotificationPreferenceDTO.class, PatientNotificationDeliveryDTO.class);
    private static final Pattern BUILDS_CONSENT_DTO = Pattern.compile(
        "\\b(NotificationPreferenceDTO|PatientNotificationDeliveryDTO)\\.builder\\(\\)"
            + "|new (NotificationPreferenceDTO|PatientNotificationDeliveryDTO)\\(");
    private static final Pattern SETS_RECORDER = Pattern.compile(
        "\\.(set)?([Cc]onsent)?[Rr]ecordedBy(Name|Kind)\\(");

    @Test
    void consentDtosCarryNoRecorderAccountId() {
        for (Class<?> dto : CONSENT_DTOS) {
            List<String> uuidFields = Arrays.stream(dto.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> field.getType() == UUID.class)
                .map(Field::getName).toList();
            // The row's own id and the patient's id; never who recorded the choice.
            assertThat(uuidFields).as("account ids on %s", dto.getSimpleName()).isSubsetOf("id", "patientId");
            assertThat(Arrays.stream(dto.getDeclaredFields()).map(Field::getName))
                .as("recorder fields on %s", dto.getSimpleName())
                .filteredOn(name -> name.toLowerCase(java.util.Locale.ROOT).contains("recordedby"))
                .isSubsetOf("recordedByName", "recordedByKind", "consentRecordedByName", "consentRecordedByKind");
        }
    }

    @Test
    void recorderNamesAreSetOnlyByTheScopedResolverAndEveryBuiltDtoIsLabelled() throws IOException {
        Map<Path, String> sources = sources();
        List<String> setters = new ArrayList<>();
        List<String> builders = new ArrayList<>();
        sources.forEach((path, text) -> {
            boolean consentDto = text.contains("NotificationPreferenceDTO") || text.contains("PatientNotificationDeliveryDTO");
            if (consentDto && SETS_RECORDER.matcher(text).find()) setters.add(path.getFileName().toString());
            if (BUILDS_CONSENT_DTO.matcher(text).find()) {
                builders.add(path.getFileName().toString());
                assertThat(text).as("labelled through ConsentRecorderLabels in %s", path).contains("labels.apply(dto, ");
            }
        });
        assertThat(setters).containsExactly("ConsentRecorderLabels.java");
        assertThat(builders).containsExactlyInAnyOrder("NotificationPreferenceViews.java",
            "PatientNotificationDeliveryAdminService.java");
    }

    @Test
    void staffSurfacesUseTheHospitalScopedResolverAndOnlyTheOwnerViewUsesTheOwnerOne() throws IOException {
        String labels = Files.readString(LABELS).replace(CR, "");
        String staff = labels.substring(labels.indexOf("public Labels forStaffViewer("),
            labels.indexOf("public Labels forAccountOwner("));
        assertThat(staff).contains("findStaffActorNamesAtHospital(").doesNotContain("findClinicalActorNames(");
        assertThat(labels).contains("private Labels(");

        List<String> staffCallers = new ArrayList<>();
        List<String> ownerCallers = new ArrayList<>();
        sources().forEach((path, text) -> {
            if (path.endsWith("ConsentRecorderLabels.java")) return;
            if (text.contains(".forStaffViewer(")) staffCallers.add(path.getFileName().toString());
            if (text.contains(".forAccountOwner(")) ownerCallers.add(path.getFileName().toString());
            if (path.toString().replace('\\', '/').contains("/service/notification/")) {
                assertThat(text).as("unscoped recorder names in %s", path).doesNotContain("findClinicalActorNames(");
            }
        });
        assertThat(staffCallers).containsExactlyInAnyOrder("PatientChannelPreferenceService.java",
            "PatientNotificationDeliveryAdminService.java");
        // The owner view: the authenticated account reading its own choices (/notifications and /me).
        assertThat(ownerCallers).containsExactly("NotificationServiceImpl.java");
    }

    @Test
    void thePatientsOwnRecordRequestsNeverCarryAStaffAccountId() throws IOException {
        String roi = Files.readString(MAIN.resolve("com/example/hms/service/roi/RoiRequestService.java"))
            .replace(CR, "");
        String self = roi.substring(roi.indexOf("public List<RoiRequestResponseDTO> requestsForSelf("));
        self = self.substring(0, self.indexOf("\n    }\n"));
        // QA D4270: each row is mapped by the patient mapper, then told whether its copy can be downloaded.
        assertThat(self).contains("mapper.toPatientDto(row)").doesNotContain("mapper::toDto").doesNotContain("mapper.toDto(");
        assertThat(Files.readString(MAIN.resolve("com/example/hms/mapper/RoiRequestMapper.java")))
            .contains("dto.setDecisionActorUserId(null);");
    }

    private static Map<Path, String> sources() throws IOException {
        Map<Path, String> out = new java.util.TreeMap<>();
        try (var paths = Files.walk(MAIN)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                out.put(path, Files.readString(path));
            }
        }
        return out;
    }
}
