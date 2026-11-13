package com.example.hms.architecture;

import com.example.hms.payload.dto.AdmissionUpdateRequestDTO;
import com.example.hms.validation.RejectsUnknownFields;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1672 (r44): the admission edit dialog let the clinician change the chief
 * complaint, type, date and time and admitting clinician; the update took none
 * of them, answered 200, kept the old values, and the portal said "Admission
 * updated". One rule, held here:
 * <ul>
 *   <li>every form control of the admission dialog is either a property the
 *       update takes, or read-only ({@code [disabled]} / {@code [readonly]})
 *       while editing;</li>
 *   <li>the portal's update body names only properties the update takes;</li>
 *   <li>the update refuses, by name, any other property
 *       ({@link RejectsUnknownFields}), so a change it would drop is a 400.</li>
 * </ul>
 */
class AdmissionEditDialogGuardTest {

    private static final Path HTML = Path.of("..", "hospital-portal/src/app/admissions/admissions.html");
    private static final Path TS = Path.of("..", "hospital-portal/src/app/admissions/admissions.ts");

    @Test
    void everyEditableControlOfTheEditDialogIsSaved() throws IOException {
        String html = Files.readString(HTML, StandardCharsets.UTF_8);
        int start = html.indexOf("@if (showModal())");
        int end = html.indexOf("@if (showDeleteConfirm())", start);
        assertThat(start).as("the admit/edit dialog").isNotNegative();
        assertThat(end).as("the end of the admit/edit dialog").isGreaterThan(start);
        String dialog = html.substring(start, end);

        Set<String> taken = updateProperties();
        List<String> problems = new ArrayList<>();
        Matcher control = Pattern.compile("<(?:input|select|textarea)\\b[^>]*>", Pattern.DOTALL).matcher(dialog);
        int bound = 0;
        while (control.find()) {
            Matcher binding = Pattern.compile("\\[(?:\\(ngModel\\)|value)]=\"form\\.(\\w+)\"").matcher(control.group());
            if (!binding.find() || control.group().contains("type=\"hidden\"")) {
                continue;
            }
            bound++;
            String property = binding.group(1);
            boolean readOnlyWhenEditing = Pattern.compile("\\[(?:disabled|readonly)]=\"editing\\(\\)\"")
                .matcher(control.group()).find();
            if (!taken.contains(property) && !readOnlyWhenEditing) {
                problems.add("form." + property + ": editable while editing, but the update does not take it");
            }
        }
        assertThat(bound).as("the dialog's bound controls were found").isGreaterThan(5);
        // The patient chip's "change patient" is not offered while editing.
        Matcher clear = Pattern.compile("@if \\(!editing\\(\\)\\) \\{\\s*<button[^>]*clearPatient\\(\\)", Pattern.DOTALL)
            .matcher(dialog);
        if (!clear.find()) {
            problems.add("the patient can be changed while editing (clearPatient not behind @if (!editing()))");
        }
        assertThat(problems)
            .as("an edit dialog control whose change the update drops behind a success (QA D1672)")
            .isEmpty();
    }

    @Test
    void thePortalUpdateBodyNamesOnlyWhatTheUpdateTakes() throws IOException {
        String ts = Files.readString(TS, StandardCharsets.UTF_8);
        Matcher method = Pattern.compile("private updatePayload\\(\\)[^{]*\\{\\s*return \\{(.*?)\\};", Pattern.DOTALL)
            .matcher(ts);
        assertThat(method.find()).as("admissions.ts updatePayload()").isTrue();
        Matcher key = Pattern.compile("(\\w+)\\s*:").matcher(method.group(1));
        Set<String> sent = new HashSet<>();
        while (key.find()) {
            sent.add(key.group(1));
        }
        assertThat(sent).as("the edit dialog's update body").isNotEmpty();
        assertThat(updateProperties()).as("properties the update takes").containsAll(sent);
        assertThat(ts).as("the edit sends updatePayload(), not the whole form")
            .contains("this.admissionService.update(this.editingId()!, this.updatePayload())");
    }

    @Test
    void theUpdateRefusesWhatItDoesNotTake() {
        assertThat(AdmissionUpdateRequestDTO.class.isAnnotationPresent(RejectsUnknownFields.class))
            .as("AdmissionUpdateRequestDTO refuses an unknown property by name (QA D1672)")
            .isTrue();
    }

    private static Set<String> updateProperties() {
        Set<String> names = new HashSet<>();
        for (Field field : AdmissionUpdateRequestDTO.class.getDeclaredFields()) {
            names.add(field.getName());
        }
        return names;
    }
}
