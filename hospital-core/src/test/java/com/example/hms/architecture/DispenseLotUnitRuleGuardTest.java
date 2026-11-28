package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3949 (r77): the dispensing panel compared the order's unit with each lot's unit itself and refused
 * « plaquette » against « plaquette de 10 comprimés », a lot the fill accepts. One rule decides it,
 * {@code DispenseUnitForms#fits}: the fill applies it, the dispensable-lots read sends its answer for each lot
 * ({@code unitFits}), and no pharmacy screen re-implements a unit comparison.
 */
class DispenseLotUnitRuleGuardTest {

    private static final Path SERVICE = Path.of("src/main/java/com/example/hms/service/pharmacy/DispenseServiceImpl.java");
    private static final Path RULE = Path.of("src/main/java/com/example/hms/service/pharmacy/DispenseUnitForms.java");
    private static final Path PHARMACY_PORTAL = Path.of("..", "hospital-portal/src/app/pharmacy");
    private static final Path DISPENSING = PHARMACY_PORTAL.resolve("dispensing.ts");

    /** A unit normaliser or a direct comparison of two `unit` fields: the client re-deciding the server's rule. */
    private static final Pattern CLIENT_UNIT_RULE = Pattern.compile(
        "function\\s+\\w*(?:[sS]ame\\w*Unit|[uU]nitKey|[uU]nitsMatch|[cC]ompatibleUnit)\\w*\\s*\\("
            + "|\\.(?:unit|quantityUnit)\\)*\\s*(?:===|!==|==|!=)\\s*[\\w?.(]*\\.(?:unit|quantityUnit)\\b"
            + "|\\.(?:unit|quantityUnit)\\b[^;\\n]{0,40}\\.normalize\\(");

    @Test
    void theFillAndTheDispensableLotsReadUseTheSameRule() throws IOException {
        String rule = SourceScan.stripComments(Files.readString(RULE));
        int requireAt = rule.indexOf("public static void requireCompatible(");
        String require = rule.substring(requireAt, SourceScan.closingBrace(rule, rule.indexOf('{', requireAt)));
        assertThat(require).as("the fill's check is fits()").contains("if (!fits(fillUnit, lotUnit))");

        String service = SourceScan.stripComments(Files.readString(SERVICE));
        int readAt = service.indexOf("listDispensableLots(\n");
        if (readAt < 0) {
            readAt = service.indexOf("listDispensableLots(\r\n");
        }
        assertThat(readAt).as("the dispensable-lots read").isGreaterThanOrEqualTo(0);
        String read = service.substring(readAt, SourceScan.closingBrace(service, service.indexOf('{', readAt)));
        assertThat(read).as("each lot is decided by the fill's rule for the order's unit")
            .contains("DispenseUnitForms.fits(prescription.getQuantityUnit(), e.getKey().getInventoryItem().getUnit())");
        assertThat(service).contains("dto.setUnitFits(unitFits);")
            .contains("DispenseUnitForms.requireCompatible(dto.getUnit(), stockLot.getInventoryItem().getUnit());");
    }

    @Test
    void thePanelFollowsTheServersAnswer() throws IOException {
        String dispensing = Files.readString(DISPENSING);
        assertThat(dispensing).contains("return lot.unitFits !== false;")
            .contains(".filter((lot) => lotFitsOrderUnit(lot))")
            .contains("!lotFitsOrderUnit(lot)");
    }

    @Test
    void noPharmacyScreenComparesUnitsItself() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(PHARMACY_PORTAL)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".ts") && !p.toString().endsWith(".spec.ts"))
                .toList()) {
                String source = SourceScan.stripComments(Files.readString(file));
                var matcher = CLIENT_UNIT_RULE.matcher(source);
                while (matcher.find()) {
                    String where = PHARMACY_PORTAL.relativize(file).toString().replace('\\', '/');
                    // Two reads of the same server row, compared exactly to see that its unit changed in between
                    // (the count's own expectedUnit check is exact too): not a fill-unit rule.
                    if (where.equals("stock-count.ts") && matcher.group().startsWith(".unit === fresh.unit")) {
                        continue;
                    }
                    found.add(where + ": " + matcher.group());
                }
            }
        }
        assertThat(found).as("a pharmacy screen deciding unit compatibility itself (use the server's unitFits)")
            .isEmpty();
    }

    @Test
    void theScanSeesTheOldPanelRule() {
        String old = """
            function unitKey(unit: string | null | undefined): string {
              return (unit ?? '').normalize('NFD');
            }
            export function sameDispenseUnit(lotUnit: string, orderUnit: string): boolean { return true; }
            const same = lot.unit === order.unit;
            const keyed = key(lot.unit) !== key(this.order?.quantityUnit);
            const fine = item.unit && item.unit !== 'UNIT' ? item.unit : '';
            const other = lot.unit.normalize('NFD');
            """;
        var matcher = CLIENT_UNIT_RULE.matcher(old);
        int hits = 0;
        while (matcher.find()) {
            hits++;
        }
        assertThat(hits).isEqualTo(5);
    }
}
