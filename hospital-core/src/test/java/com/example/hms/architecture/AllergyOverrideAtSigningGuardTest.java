package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Policy 3 guard (2026-11-09, provisional platform policy, not a clinical sign-off). Class: "a path
 * that signs a prescription an allergy warning applies to without the signer's documented reason for
 * that allergy". QA rounds 38-40 signed an order acknowledged at creation with no reason at all.
 *
 * <p>The acknowledgement is required in ONE place, {@code signPrescription}, before the status becomes
 * SIGNED. Every other site in the tree that puts a prescription in SIGNED is listed with why it is not
 * a first signature (it returns an order already signed to the queue); a new one fails here until
 * someone decides whether it signs (and must require the reason) or not. Co-signature, order sets and
 * the urgent dose exception all go through {@code createPrescription} or {@code signPrescription}.
 */
class AllergyOverrideAtSigningGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final String SERVICE = "service/PrescriptionServiceImpl.java";
    /** A status write or assignment of SIGNED, in any form the tree uses. */
    private static final Pattern SIGNED_WRITE =
        Pattern.compile("setStatus\\(PrescriptionStatus\\.SIGNED\\)|=\\s*PrescriptionStatus\\.SIGNED\\s*;|:\\s*PrescriptionStatus\\.SIGNED\\s*;");

    private static final Map<String, String> NOT_A_FIRST_SIGNATURE = Map.of(
        SERVICE, "the signature itself: requires the allergy reason first (asserted below)",
        "service/impl/RefillApprovalServiceImpl.java", "releases an approved refill of an order already signed",
        "service/pharmacy/StockOutRoutingServiceImpl.java", "a partner no-show returns an already-signed order to the queue",
        "service/pharmacy/DispenseServiceImpl.java", "every dispense cancelled: a signed order returns to a dispensable state",
        "service/pharmacy/PrescriptionClarificationService.java", "a resolved clarification restores the status of a signed order");

    @Test
    void everySignedWriteIsTheSignatureOrListed() throws IOException {
        TreeSet<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (SIGNED_WRITE.matcher(Files.readString(file)).find()) {
                    found.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        List<String> unlisted = new ArrayList<>(found);
        unlisted.removeAll(NOT_A_FIRST_SIGNATURE.keySet());
        assertThat(unlisted)
            .as("A new path puts a prescription in SIGNED: does it sign? If so it must require the signer's"
                + " allergy reason (requireAllergyAcknowledgedAtSigning); else list it with the reason.")
            .isEmpty();
        assertThat(found).as("A listed site no longer writes SIGNED: delete its entry")
            .containsAll(NOT_A_FIRST_SIGNATURE.keySet());
    }

    @Test
    void theSignatureRequiresTheAllergyReasonBeforeSigning() throws IOException {
        String sign = methodBody(read(SERVICE), "public PrescriptionResponseDTO signPrescription(");
        int hardStop = sign.indexOf("refuseHardStopAtSigning(");
        int allergy = sign.indexOf("requireAllergyAcknowledgedAtSigning(");
        int signed = sign.indexOf("setStatus(PrescriptionStatus.SIGNED)");
        assertThat(hardStop).as("a non-overridable stop is refused, whatever the reason").isNotNegative();
        assertThat(allergy).as("the allergy reason is required after the hard stop").isGreaterThan(hardStop);
        assertThat(signed).as("before the status becomes SIGNED").isGreaterThan(allergy);
    }

    @Test
    void anAcknowledgementOnRecordNeverPassesTheSignature() throws IOException {
        String text = read(SERVICE);
        String require = methodBody(text, "private AllergyAcknowledgement requireAllergyAcknowledgedAtSigning(");
        String stop = methodBody(text, "private Optional<AllergyStop> allergyStopAtSigning(");
        assertThat(require + stop)
            .as("an acknowledgement made at creation or edit (maybe by someone else) must not pass the signature")
            .doesNotContain("isAllergiesReviewed()")
            .doesNotContain("getOverrideReason()");
        assertThat(require)
            .as("the reason must be non-blank and tied by the token to the allergy the warning names")
            .contains("getAllergyOverrideReason()")
            .contains(".trim()")
            .contains("isEmpty()")
            .contains("tokenMatches(request, token)");
        String token = methodBody(text, "private String doseExceptionToken(");
        assertThat(token).as("the token names the allergy, not only that one exists").contains("AllergyStop::key");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    /** The text from the signature to the matching close brace. */
    private static String methodBody(String text, String signature) {
        int start = text.indexOf(signature);
        assertThat(start).as(signature).isNotNegative();
        int open = text.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }
}
