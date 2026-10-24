package com.example.hms.service;

import com.example.hms.config.MessageBundles;
import com.example.hms.enums.AbnormalFlag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D729: the record PDF printed the result flag raw ("critical",
 * "abnormal") - the chart's graded severity had no label in the PDF or
 * shared enum bundle, so the readable-code fallback showed. Every flag the
 * chart can carry now has one in every locale.
 */
class PatientRecordPdfResultFlagLabelsTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "_en", "_fr", "_es"})
    void everyResultFlagHasALabel(String bundle) throws Exception {
        Properties p = new Properties();
        for (String area : MessageBundles.AREAS) {
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream(MessageBundles.resource(area, bundle))) {
                if (in != null) {
                    p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            }
        }
        List<String> missing = new ArrayList<>();
        Stream.of(AbnormalFlag.values())
            .map(Enum::name)
            .filter(code -> !p.containsKey("pdf.record.enum." + code) && !p.containsKey("cds.enum." + code))
            .forEach(missing::add);
        assertThat(missing).as("result flags without a PDF label in '" + bundle + "'").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"_fr", "_es"})
    void theCriticalFlagIsTranslated(String bundle) throws Exception {
        Properties p = new Properties();
        for (String area : MessageBundles.AREAS) {
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream(MessageBundles.resource(area, bundle))) {
                if (in != null) {
                    p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            }
        }
        String critical = p.getProperty("pdf.record.enum.CRITICAL");
        assertThat(critical).isNotNull().isNotEqualToIgnoringCase("critical");
    }
}
