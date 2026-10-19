package com.example.hms.service;

import com.example.hms.config.MessageBundles;
import com.example.hms.enums.ImagingLaterality;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderStatus;
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
 * QA D250 (partial D85): the record PDF printed "xray … ordered" — the imaging
 * modality, status and laterality had no label in the PDF or shared enum
 * bundle, so the readable-code fallback showed. Every value now has one in
 * French and Spanish.
 */
class PatientRecordPdfImagingLabelsTest {

    @ParameterizedTest
    @ValueSource(strings = {"_fr", "_es"})
    void everyImagingCodeHasALabel(String bundle) throws Exception {
        Properties p = new Properties();
        for (String area : MessageBundles.AREAS) {
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream(MessageBundles.resource(area, bundle))) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        }
        List<String> missing = new ArrayList<>();
        Stream.of(ImagingModality.values(), ImagingOrderStatus.values(), ImagingLaterality.values())
            .flatMap(Stream::of)
            .map(Enum::name)
            .filter(code -> !p.containsKey("pdf.record.enum." + code) && !p.containsKey("cds.enum." + code))
            .forEach(missing::add);
        assertThat(missing).as("imaging codes without a PDF label in " + bundle).isEmpty();
    }
}
