package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1822 (r51) guard: a status write that copies the rest of its request
 * onto the record, field by field, erases every field the caller left out.
 * {@code PUT /imaging/orders/{id}/status {status: IN_PROGRESS}} - and the
 * portal's cancel - nulled the schedule, the appointment location, the
 * workflow notes and the pre-authorisation of the order.
 *
 * <p>In every status-writing method of production code (a method named
 * {@code update…Status…}, {@code change…Status…}, {@code transition…Status…}
 * or {@code patch…Status…}; entities excepted), a copy
 * {@code record.setX(request.getX())} is allowed only for the status itself
 * and its reason or note, or behind a presence check on the same getter
 * ({@code if (request.getX() != null)}). Anything else is the D1822 shape.
 * A heuristic over the method's own body: a copy made in a helper it calls is
 * read where that helper is named like a status write, or by the unit test of
 * the service ({@code ImagingOrderServiceImplTest#aStatusWriteKeepsEveryNonStatusField_D1822}).
 */
@DisplayName("D1822: a status write never erases the fields its request leaves out")
class StatusWriteKeepsAbsentFieldsGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static final Pattern STATUS_WRITER = Pattern.compile(
        "\\n    (?:(?:private|public|protected|static|final|synchronized)\\s++)++(?:[\\w<>,.?\\[\\]]++\\s++)++"
            + "((?:update|change|transition|patch)\\w*Status\\w*+)\\s*+\\([^;{]*+\\{");

    /** {@code target.setX(source.getX())} - the same property, from another object. */
    private static final Pattern COPY = Pattern.compile("(\\w++)\\.set(\\w++)\\(\\s*+(\\w++)\\.get(\\w++)\\(\\)\\s*+\\)");

    /** What a status write is for: the status and why it changed. */
    private static final Set<String> STATUS_PROPERTIES = Set.of(
        "Status", "StatusReason", "Reason", "Notes", "StatusNotes", "CancellationReason", "CancelReason");

    @Test
    @DisplayName("every status write copies only the status and its reason, or what the request carries")
    void statusWritesKeepTheFieldsTheyAreNotSent() throws IOException {
        List<Path> javaFiles;
        try (Stream<Path> files = Files.walk(MAIN)) {
            javaFiles = files.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.startsWith(MAIN.resolve("model")))
                .toList();
        }
        List<String> offenders = new ArrayList<>();
        int writers = 0;
        for (Path file : javaFiles) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            List<String> found = offenders(file.getFileName().toString().replace(".java", ""), source);
            writers += countWriters(source);
            offenders.addAll(found);
        }
        assertThat(writers).as("the scan found the status writers").isGreaterThan(10);
        assertThat(offenders)
            .as("a status write that copies a field the request may leave out erases it (QA D1822): "
                + "guard the copy with `if (request.getX() != null)`" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1822 shape and accepts its fix")
    void catchesTheD1822Shape() {
        String before = """
            class S {
                public Dto updateOrderStatus(UUID orderId, StatusUpdateRequest request) {
                    Order order = load(orderId);
                    order.setStatus(request.getStatus());
                    order.setScheduledDate(request.getScheduledDate());
                    order.setWorkflowNotes(request.getWorkflowNotes());
                    order.setCancellationReason(request.getCancellationReason());
                    return map(order);
                }
            }
            """;
        assertThat(offenders("S", before))
            .containsExactly("S#updateOrderStatus: order.setScheduledDate(request.getScheduledDate())",
                "S#updateOrderStatus: order.setWorkflowNotes(request.getWorkflowNotes())");

        String after = before
            .replace("order.setScheduledDate(request.getScheduledDate());",
                "if (request.getScheduledDate() != null) {\n            order.setScheduledDate(request.getScheduledDate());\n        }")
            .replace("order.setWorkflowNotes(request.getWorkflowNotes());",
                "if (request.getWorkflowNotes() != null) order.setWorkflowNotes(request.getWorkflowNotes());");
        assertThat(offenders("S", after)).isEmpty();

        String otherMethod = before.replace("updateOrderStatus", "updateOrder");
        assertThat(offenders("S", otherMethod)).as("only status writes are read").isEmpty();
    }

    private static int countWriters(String source) {
        int count = 0;
        Matcher m = STATUS_WRITER.matcher(source);
        while (m.find()) {
            count++;
        }
        return count;
    }

    static List<String> offenders(String className, String source) {
        List<String> offenders = new ArrayList<>();
        Matcher method = STATUS_WRITER.matcher(source);
        while (method.find()) {
            int open = method.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            String body = source.substring(open, close);
            Matcher copy = COPY.matcher(body);
            while (copy.find()) {
                String property = copy.group(2);
                boolean sameProperty = property.equals(copy.group(4));
                boolean fromAnother = !copy.group(1).equals(copy.group(3));
                if (!sameProperty || !fromAnother || STATUS_PROPERTIES.contains(property)) {
                    continue;
                }
                String guard = copy.group(3) + ".get" + property + "() != null";
                String before = body.substring(Math.max(0, copy.start() - 160), copy.start());
                if (before.replaceAll("\\s++", " ").contains(guard)) {
                    continue;
                }
                offenders.add(className + "#" + method.group(1) + ": " + copy.group());
            }
        }
        return offenders;
    }
}
