package com.example.hms.service.pharmacy;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.repository.pharmacy.StockLotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D964: first expired, first out. The rule itself, and a codebase guard
 * that every service taking stock off a chosen lot applies it (the fill, the
 * counter sale and the transfer each have a wiring test in their own suite).
 */
class FefoLotPolicyTest {

    private final StockLotRepository repository = mock(StockLotRepository.class);
    private final FefoLotPolicy policy = new FefoLotPolicy(repository);
    private final InventoryItem item = InventoryItem.builder().build();
    private StockLot shortDated;
    private StockLot longDated;

    @BeforeEach
    void setUp() {
        item.setId(UUID.randomUUID());
        shortDated = lot("R16-PCM-COURT", LocalDate.now().plusMonths(2), "100");
        longDated = lot("R16-PCM-LONG", LocalDate.now().plusMonths(18), "200");
        when(repository.findAvailableLotsByFEFO(item.getId())).thenReturn(List.of(shortDated, longDated));
    }

    @Test
    void takingTheLaterLotWhileTheShorterOneCanServeNeedsAReasonAndNamesTheLotToTake() {
        BigDecimal twenty = new BigDecimal("20");
        assertThatThrownBy(() -> policy.requireFefoOrReason(longDated, twenty, "  "))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getMessageKey()).isEqualTo("pharmacy.fefo.reasonRequired");
                    assertThat(((BusinessException) e).getArgs()[0]).isEqualTo("R16-PCM-COURT");
                });
    }

    @Test
    void aReasonLetsTheLaterLotGoAndIsReturnedToBeRecorded() {
        assertThat(policy.requireFefoOrReason(longDated, new BigDecimal("20"), "  lot court réservé  "))
                .isEqualTo("lot court réservé");
    }

    @Test
    void theShortestDatedLotNeedsNoReasonAndAReasonSentAnywayIsNotRecorded() {
        assertThat(policy.requireFefoOrReason(shortDated, new BigDecimal("20"), "rien")).isNull();
    }

    /**
     * QA D1083: the refusal said the earlier lot "covers this quantity" when
     * it held 250 of the 300 asked. It now names what is left on it (less what
     * earlier lines of the same movement take), and no locale of the message,
     * on the server or on the screen, claims the earlier lot covers anything.
     */
    @Test
    void theRefusalNamesWhatIsLeftOnTheEarlierLot_D1083() {
        BigDecimal hundredFifty = new BigDecimal("150");
        assertThatThrownBy(() -> policy.requireFefoOrReason(longDated, hundredFifty, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getArgs()[2]).isEqualTo("100"));
        java.util.Map<UUID, BigDecimal> partly = java.util.Map.of(shortDated.getId(), new BigDecimal("90"));
        assertThatThrownBy(() -> policy.requireFefoOrReason(longDated, BigDecimal.ONE, null, partly))
                .satisfies(e -> assertThat(((BusinessException) e).getArgs()[2]).isEqualTo("10"));
    }

    @Test
    void noLocaleOfTheFefoMessageClaimsTheEarlierLotCoversTheQuantity_D1083() throws IOException {
        java.util.regex.Pattern claim = java.util.regex.Pattern.compile("(?i)covers|can serve|couvre|cubre");
        List<String> problems = new ArrayList<>();
        for (String suffix : List.of("", "_en", "_fr", "_es")) {
            java.util.Properties bundle = new java.util.Properties();
            try (var reader = Files.newBufferedReader(
                    Path.of("src/main/resources/i18n/messages-pharmacy" + suffix + ".properties"), StandardCharsets.UTF_8)) {
                bundle.load(reader);
            }
            String text = bundle.getProperty("pharmacy.fefo.reasonRequired", "");
            if (claim.matcher(text).find() || !text.contains("{2}")) {
                problems.add("messages-pharmacy" + suffix + ": " + text);
            }
        }
        for (String lang : List.of("en", "fr", "es")) {
            String json = Files.readString(Path.of("../hospital-portal/src/assets/i18n/" + lang + ".json"), StandardCharsets.UTF_8);
            java.util.regex.Matcher warning = java.util.regex.Pattern.compile("\"FEFO_WARNING\": \"([^\"]*)\"").matcher(json);
            while (warning.find()) {
                if (claim.matcher(warning.group(1)).find() || !warning.group(1).contains("{{left}}")) {
                    problems.add(lang + ".json FEFO_WARNING: " + warning.group(1));
                }
            }
        }
        assertThat(problems).as("a FEFO message that says the earlier lot covers the quantity (QA D1083)").isEmpty();
    }

    /**
     * Review of #975: a counter sale is built line by line before any stock
     * moves; 100 already taken off the short lot by an earlier line leaves it
     * nothing, so the next line from the long lot follows FEFO.
     */
    @Test
    void whatEarlierLinesTakeIsNotCountedAsStillOnTheShorterLot() {
        java.util.Map<UUID, BigDecimal> taken = java.util.Map.of(shortDated.getId(), new BigDecimal("100"));
        assertThat(policy.requireFefoOrReason(longDated, new BigDecimal("5"), null, taken)).isNull();
        java.util.Map<UUID, BigDecimal> partly = java.util.Map.of(shortDated.getId(), new BigDecimal("90"));
        BigDecimal five = new BigDecimal("5");
        assertThatThrownBy(() -> policy.requireFefoOrReason(longDated, five, null, partly))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.fefo.reasonRequired");
    }

    /**
     * QA D1029 (round 17) inverts the round-16 rule "a shorter-dated lot that
     * cannot cover the movement does not count": 130 tablets left the 2028 lot
     * while 120 sat in the 2027 lot, with no reason asked. Any stock left on a
     * shorter-dated lot comes first; the screen splits the fill across lots.
     */
    @Test
    void aShorterDatedLotThatCannotCoverTheMovementStillComesFirst_D1029() {
        BigDecimal hundredFifty = new BigDecimal("150");
        assertThatThrownBy(() -> policy.requireFefoOrReason(longDated, hundredFifty, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getArgs()[0]).isEqualTo("R16-PCM-COURT"));
        assertThat(policy.requireFefoOrReason(longDated, hundredFifty, "patient part en brousse")).isNotNull();
    }

    @Test
    void anOverlongReasonIsRefused() {
        String overlong = "x".repeat(301);
        assertThatThrownBy(() -> policy.requireFefoOrReason(longDated, BigDecimal.ONE, overlong))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.fefo.reasonTooLong");
    }

    @Test
    void noLotMeansNoRule() {
        assertThat(policy.requireFefoOrReason(null, BigDecimal.ONE, null)).isNull();
    }

    /**
     * The guard: a service that takes stock off a lot it was handed (an atomic
     * {@code decrementRemaining}) or records a sale line against a lot must
     * apply the FEFO rule. A new movement path that forgets it fails here.
     */
    @ParameterizedTest
    @ValueSource(strings = {"decrementRemaining(", "SaleLine.builder()"})
    void everyServiceThatTakesStockOffAChosenLotAppliesTheRule(String marker) throws IOException {
        Path root = Path.of("src", "main", "java", "com", "example", "hms", "service");
        List<String> missing = new ArrayList<>();
        int found = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!source.contains(marker)) {
                    continue;
                }
                found++;
                if (!source.contains("fefoLotPolicy.requireFefoOrReason(")) {
                    missing.add(file.getFileName().toString());
                }
            }
        }
        assertThat(found).as("services matching %s", marker).isPositive();
        assertThat(missing).as("services taking stock off a chosen lot without the FEFO rule (QA D964)").isEmpty();
    }

    private StockLot lot(String number, LocalDate expiry, String remaining) {
        StockLot lot = StockLot.builder().inventoryItem(item).lotNumber(number).expiryDate(expiry)
                .remainingQuantity(new BigDecimal(remaining)).build();
        lot.setId(UUID.randomUUID());
        return lot;
    }
}
