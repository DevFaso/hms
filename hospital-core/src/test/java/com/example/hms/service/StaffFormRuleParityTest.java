package com.example.hms.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1096 review (D1536 class): the server asks a staff record only for what the
 * /staff form collects. The form sends a specialization only for its
 * {@code specialtyTitles} and a licence only for its {@code licencedTitles}
 * (staff-list.ts, submitForm); a server rule wider than the form made a doctor
 * or radiologist role on any other job title impossible to save from the UI.
 * The two lists are held equal here.
 */
class StaffFormRuleParityTest {

    private static final Path FORM = Path.of("../hospital-portal/src/app/staff/staff-list.ts");

    @Test
    void theSpecializationTitlesAreTheForms() throws IOException {
        assertThat(formSet("specialtyTitles")).isEqualTo(StaffServiceImpl.SPECIALTY_JOB_TITLES);
    }

    @Test
    void theLicenceTitlesAreTheForms() throws IOException {
        assertThat(formSet("licencedTitles")).isEqualTo(StaffServiceImpl.CLINICAL_JOB_TITLES);
    }

    private static Set<String> formSet(String name) throws IOException {
        String source = Files.readString(FORM, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile(Pattern.quote(name) + "\\s*=\\s*new Set\\(\\[(.*?)]\\)", Pattern.DOTALL)
            .matcher(source);
        assertThat(m.find()).as(name + " in staff-list.ts").isTrue();
        Set<String> titles = new TreeSet<>();
        Matcher title = Pattern.compile("'([A-Z_]+)'").matcher(m.group(1).replaceAll("//[^\n]*", ""));
        while (title.find()) {
            titles.add(title.group(1));
        }
        assertThat(titles).as(name).isNotEmpty();
        return titles;
    }
}
