package com.example.hms.utility;

import com.example.hms.hl7.mllp.Hl7AckBuilder;
import com.example.hms.hl7.mllp.Hl7MessageHeader;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Class guard (owner, 2026-11-19): every timestamp HMS writes into an outbound
 * HL7 message is an explicit UTC instant, {@code yyyyMMddHHmmss+0000}. A bare
 * {@code yyyyMMddHHmmss} is read by the receiver in its own zone — the message
 * said 15:30 while the screen said 10:30.
 *
 * <p>Two halves: every message we build (order, result, acknowledgement) has
 * no zone-less timestamp in any field, and no HL7 source formats one.
 */
class Hl7OutboundTimestampGuardTest {

    /** A field (or component) that is a TS/DTM: 8–14 digits, optional fraction, optional offset. */
    private static final Pattern TS_FIELD = Pattern.compile("^(\\d{8}|\\d{10}|\\d{12}|\\d{14})(\\.\\d{1,4})?([+-]\\d{4})?$");

    private final Hl7v2MessageBuilder builder = new Hl7v2MessageBuilder();

    private static LabOrder order() {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setTestCode("GLU");
        definition.setName("Glucose");
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setLastName("Ouedraogo");
        patient.setFirstName("Awa");
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setLabTestDefinition(definition);
        order.setPatient(patient);
        return order;
    }

    /** Every TS-shaped field or component of a message, as "SEG-n: value". */
    private static List<String> zonelessTimestamps(String message) {
        List<String> offenders = new ArrayList<>();
        for (String segment : message.split("\r\n|\r|\n")) {
            String[] fields = segment.split("\\|", -1);
            for (int i = 1; i < fields.length; i++) {
                for (String part : fields[i].split("[\\^~&]", -1)) {
                    Matcher m = TS_FIELD.matcher(part);
                    if (m.matches() && m.group(3) == null) {
                        offenders.add(fields[0] + "-" + i + ": " + part);
                    }
                }
            }
        }
        return offenders;
    }

    private static List<String> timestamps(String message) {
        List<String> found = new ArrayList<>();
        for (String segment : message.split("\r")) {
            for (String field : segment.split("\\|", -1)) {
                if (TS_FIELD.matcher(field).matches()) {
                    found.add(field);
                }
            }
        }
        return found;
    }

    @Test
    void theOrderMessageWritesOnlyUtcInstants() {
        LabSpecimen specimen = new LabSpecimen();
        specimen.setLabOrder(order());
        specimen.setAccessionNumber("ACC-1");
        specimen.setCollectedAt(LocalDateTime.of(2026, 11, 19, 10, 30));

        String oml = builder.buildOml021(specimen);

        assertThat(zonelessTimestamps(oml)).isEmpty();
        // MSH-7, ORC-9 and OBR-7 (the stored UTC collection time, as-is).
        assertThat(timestamps(oml)).hasSize(3).contains("20261119103000+0000");
    }

    @Test
    void theResultMessageWritesOnlyUtcInstants() {
        LabResult result = LabResult.builder()
            .labOrder(order())
            .resultValue("5.6")
            .resultDate(LocalDateTime.of(2026, 11, 19, 15, 30))
            .build();

        String oru = builder.buildOruR01(result);

        assertThat(zonelessTimestamps(oru)).isEmpty();
        // MSH-7, OBR-7 and OBX-14.
        assertThat(timestamps(oru)).hasSize(3);
        assertThat(timestamps(oru).subList(1, 3)).containsOnly("20261119153000+0000");
        // ...and our own inbound parser reads the instant back unchanged.
        assertThat(builder.parseOruR01(oru)).singleElement()
            .extracting(Hl7v2MessageBuilder.ParsedObservation::resultDate)
            .isEqualTo(LocalDateTime.of(2026, 11, 19, 15, 30));
    }

    @Test
    void theAcknowledgementWritesOnlyUtcInstants() {
        Hl7MessageHeader inbound = new Hl7MessageHeader("|", "^~\\&", "LIS", "LAB", "HMS", "HOSP",
            "20261119153000", "ORU^R01", "MSG-1", "P", "2.5.1");

        String ack = Hl7AckBuilder.buildAck(inbound, Hl7AckBuilder.AckCode.AA, null);

        assertThat(zonelessTimestamps(ack)).isEmpty();
        assertThat(timestamps(ack)).singleElement().asString().endsWith("+0000");
    }

    @Test
    void theInboundParserStillAcceptsBothForms() {
        String withoutOffset = "MSH|^~\\&|LIS|LAB|HMS|HOSP|20261119153000||ORU^R01|M1|P|2.5.1\r"
            + "PID|1||p\rOBR|1|o||GLU|||20261119153000\r"
            + "OBX|1|NM|GLU||5.6|mmol/L||N|||F|||20261119153000\r";
        String withOffset = withoutOffset.replace("|20261119153000\r", "|20261119163000+0100\r");

        assertThat(builder.parseOruR01(withoutOffset)).singleElement()
            .extracting(Hl7v2MessageBuilder.ParsedObservation::resultDate)
            .isEqualTo(LocalDateTime.of(2026, 11, 19, 15, 30));
        assertThat(builder.parseOruR01(withOffset)).singleElement()
            .extracting(Hl7v2MessageBuilder.ParsedObservation::resultDate)
            .isEqualTo(LocalDateTime.of(2026, 11, 19, 15, 30));
    }

    /** Source half: no HL7 writer formats a zone-less timestamp pattern of its own. */
    @Test
    void noHl7SourceDeclaresAZonelessTimestampPattern() throws Exception {
        Path main = Path.of("src", "main", "java", "com", "example", "hms");
        List<Path> roots = List.of(main.resolve("hl7"), main.resolve("utility"), main.resolve("service").resolve("integration"));
        List<String> offenders = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String name = file.getFileName().toString();
                    if (!name.startsWith("Hl7") && !name.contains("Mllp")) {
                        continue;
                    }
                    String text = Files.readString(file);
                    if (text.contains("ofPattern(\"yyyyMMddHHmmss\")") && !name.equals("Hl7Timestamps.java")) {
                        offenders.add(file.toString());
                    }
                }
            }
        }
        assertThat(offenders)
            .as("format HL7 timestamps through Hl7Timestamps (explicit +0000), never a bare yyyyMMddHHmmss")
            .isEmpty();
    }
}
