package com.example.hms.architecture;

import com.example.hms.service.impl.ImagingOrderServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3450 (High) / D3254 (r70): an imaging order's catalogue exam is what prices it and what decides a family
 * proxy's read, so it is written in ONE place: {@code ImagingCatalogueService.applyTo}, which checks the item
 * against the order's own hospital. The class this guards:
 * <ul>
 *   <li>a write path that maps a request to an order and forgets the catalogue (the order would keep a stale exam,
 *       or none, and fall back to free text);</li>
 *   <li>a mapper that copies {@code catalogueItemId} from the request (an item of another hospital, or a cleared
 *       exam's id on an obstetric study, taken on the client's word);</li>
 *   <li>a proxy read of imaging that asks the rule without the catalogue's flag.</li>
 * </ul>
 */
@DisplayName("D3450 / D3254: an imaging order's catalogue exam is set only by the catalogue service")
class ImagingCatalogueOrderGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static String source(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative), StandardCharsets.UTF_8);
    }

    private static int count(String text, String needle) {
        Matcher matcher = Pattern.compile(Pattern.quote(needle)).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    @Test
    @DisplayName("every path that maps a request onto an order applies the catalogue exam")
    void everyRequestMappingAppliesTheCatalogue() throws IOException {
        String service = source("service/impl/ImagingOrderServiceImpl.java");
        int mappings = count(service, "imagingOrderMapper.toEntity(")
            + count(service, "imagingOrderMapper.updateEntityFromRequest(");
        // The helper's own declaration is one of the occurrences.
        int applications = count(service, "applyCatalogueExam(") - 1;

        assertThat(mappings).as("the scan found the service's request mappings").isGreaterThanOrEqualTo(2);
        assertThat(applications).as("each request mapping is followed by applyCatalogueExam").isEqualTo(mappings);
        assertThat(service).contains("imagingCatalogueService.applyTo(order, catalogueItemId, hospitalId)");
    }

    @Test
    @DisplayName("the mapper never copies the catalogue item from the request, and nothing else writes it")
    void onlyTheCatalogueServiceWritesTheItem() throws IOException {
        String mapper = source("mapper/ImagingOrderMapper.java");
        // The response carries the order's own item (one builder call); the request's is never copied.
        assertThat(mapper).doesNotContain("dto.getCatalogueItemId()").doesNotContain("request.getCatalogueItemId()")
            .doesNotContain("setCatalogueItemId(");
        assertThat(count(mapper, "catalogueItemId(")).isEqualTo(1);
        try (var files = Files.walk(MAIN)) {
            assertThat(files.filter(file -> file.toString().endsWith(".java"))
                .filter(file -> {
                    try {
                        return Files.readString(file, StandardCharsets.UTF_8).contains(".setCatalogueItemId(");
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                .map(file -> MAIN.relativize(file).toString().replace('\\', '/')))
                .containsExactly("service/imaging/ImagingCatalogueService.java");
        }
    }

    @Test
    @DisplayName("the proxy's imaging read and the proxy's invoice read ask the rule WITH the catalogue's flag")
    void proxyReadsAskWithTheFlag() throws IOException {
        String portal = source("service/imaging/PatientImagingPortalService.java");
        assertThat(portal).contains("imagingCatalogueService.pregnancyFlagsOf(orders)")
            .contains("PregnancyRevealingStudyRule.judge(o, pregnancyFlags.get(o.getId()), o.getPatient())")
            .doesNotContain("mayRevealPregnancy(o)");
        String filter = source("service/recordaccess/ProxyRecordFilter.java");
        assertThat(filter).contains("imagingCatalogueService.pregnancyFlagsOf(orders)")
            .contains("billsNoPregnancyRevealingStudy(invoice, visit)");
    }

    @Test
    @DisplayName("an edit that names no exam keeps the order's exam only while the study is still named the same")
    void anEditKeepsTheExamWhileTheNameStands() throws Exception {
        Method kept = ImagingOrderServiceImpl.class.getDeclaredMethod("keptCatalogueItem", UUID.class, String.class,
            String.class);
        kept.setAccessible(true);
        UUID item = UUID.randomUUID();

        assertThat(kept.invoke(null, item, "Radiographie du thorax", " radiographie du THORAX ")).isEqualTo(item);
        assertThat(kept.invoke(null, item, "Radiographie du thorax", "Échographie obstétricale")).isNull();
        assertThat(kept.invoke(null, item, null, "Radiographie du thorax")).isNull();
        assertThat(kept.invoke(null, null, "Radiographie du thorax", "Radiographie du thorax")).isNull();
    }
}
