package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3154 (r70a): a caregiver reads a study by the visit it was ordered in, so
 * an imaging order must name its visit whenever the patient has one. One rule,
 * held here for every way an imaging order is created:
 * <ul>
 *   <li>an {@code ImagingOrder} is built in one place only,
 *       {@code ImagingOrderMapper.toEntity};</li>
 *   <li>{@code toEntity} is called by {@code ImagingOrderServiceImpl.createOrder}
 *       only, which sets the order's visit from {@code visitOf(...)}: the visit
 *       the caller named (checked to be this patient's, here), else the
 *       patient's latest open visit at this hospital;</li>
 *   <li>every other caller of {@code createOrder} (an order set, ...) hands it
 *       the visit it has ({@code setEncounterId}); the portal's form sends the
 *       visit picked (imaging-r70a.spec.ts).</li>
 * </ul>
 * A new path that creates an imaging order without its visit fails here.
 */
class ImagingOrderVisitGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final String MAPPER = "mapper/ImagingOrderMapper.java";
    private static final String SERVICE = "service/impl/ImagingOrderServiceImpl.java";
    private static final String CONTROLLER = "controller/ImagingOrderController.java";

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String rel(Path file) {
        return MAIN.relativize(file).toString().replace('\\', '/');
    }

    @Test
    void anImagingOrderIsBuiltOnlyByTheMapper() throws IOException {
        List<String> builders = new ArrayList<>();
        Pattern built = Pattern.compile("\\bnew\\s+ImagingOrder\\s*\\(|\\bImagingOrder\\.builder\\(\\)");
        for (Path file : sources()) {
            if (built.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                builders.add(rel(file));
            }
        }
        assertThat(builders).as("where an ImagingOrder is built").containsExactly(MAPPER);
    }

    @Test
    void theMappedOrderIsCreatedOnlyThroughCreateOrderWhichSetsItsVisit() throws IOException {
        List<String> callers = new ArrayList<>();
        for (Path file : sources()) {
            if (Files.readString(file, StandardCharsets.UTF_8).contains("imagingOrderMapper.toEntity(")) {
                callers.add(rel(file));
            }
        }
        assertThat(callers).as("who turns a request into a new ImagingOrder").containsExactly(SERVICE);

        String service = Files.readString(MAIN.resolve(SERVICE), StandardCharsets.UTF_8);
        int start = service.indexOf("public ImagingOrderResponseDTO createOrder(");
        int end = service.indexOf("imagingOrderRepository.save(", start);
        assertThat(start).as("createOrder").isNotNegative();
        String create = service.substring(start, end);
        assertThat(create).as("createOrder maps the request once, and sets its visit before saving it")
            .containsOnlyOnce("imagingOrderMapper.toEntity(")
            .contains("setEncounterId(visitOf(request.getEncounterId()");
        int visitOf = service.indexOf("private UUID visitOf(");
        assertThat(visitOf).as("visitOf").isNotNegative();
        Matcher close = Pattern.compile("\\R    }\\R").matcher(service);
        assertThat(close.find(visitOf)).as("the end of visitOf").isTrue();
        String visit = service.substring(visitOf, close.start());
        assertThat(visit).as("with no visit named, the patient's open visit at this hospital is taken")
            .contains("findByPatient_IdAndHospital_IdAndStatusNotIn(")
            .contains("EncounterStatus.CLOSED");
    }

    @Test
    void everyCallerOfCreateOrderHandsItTheVisitItHas() throws IOException {
        Pattern call = Pattern.compile("\\bimagingOrderService\\.createOrder\\(");
        List<String> missing = new ArrayList<>();
        int callers = 0;
        for (Path file : sources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher found = call.matcher(source);
            if (!found.find()) {
                continue;
            }
            callers++;
            // The controller passes the client's request through: its visit is the one the form picked.
            if (rel(file).equals(CONTROLLER)) {
                continue;
            }
            if (!source.contains(".setEncounterId(")) {
                missing.add(rel(file));
            }
        }
        assertThat(callers).as("the callers of createOrder were found").isGreaterThanOrEqualTo(2);
        assertThat(missing).as("a path that creates an imaging order without the visit it has (QA D3154)")
            .isEmpty();
    }
}
