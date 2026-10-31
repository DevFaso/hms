package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 18 guard. Class: "a path that makes a prescription SIGNED without the
 * prescriber's re-authentication for a controlled drug".
 *
 * <p>The re-authentication lives in ONE place, {@code signPrescription}, and
 * must run before the status becomes SIGNED there. Every other site in the
 * tree that writes {@code PrescriptionStatus.SIGNED} is listed below with why
 * it is not a signature; a new one fails here until someone decides whether it
 * signs (and must re-authenticate) or not.
 */
class ControlledSigningReauthenticationGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final String SIGNED_WRITE = "setStatus(PrescriptionStatus.SIGNED)";

    /** Sites that write SIGNED without being a prescriber's signature, with the reason. */
    private static final Map<String, String> NOT_A_SIGNATURE = Map.of(
        "service/PrescriptionServiceImpl.java",
        "the signature itself: re-authenticates a controlled drug first (asserted below)",
        "service/impl/RefillApprovalServiceImpl.java",
        "releases an approved refill of an order already signed; a controlled drug is never refilled (QA D719)",
        "service/pharmacy/StockOutRoutingServiceImpl.java",
        "a partner no-show returns an already-signed order to the in-house queue");

    @Test
    void everySignedWriteIsTheSignatureOrListed() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                int count = countOf(text, SIGNED_WRITE);
                if (count > 0) {
                    found.put(MAIN.relativize(file).toString().replace('\\', '/'), count);
                }
            }
        }
        List<String> unlisted = new ArrayList<>();
        for (String site : found.keySet()) {
            if (!NOT_A_SIGNATURE.containsKey(site)) {
                unlisted.add(site);
            }
        }
        assertThat(unlisted)
            .as("A new path writes PrescriptionStatus.SIGNED: does it sign? If so it must re-authenticate a"
                + " controlled drug (PrescriberReauthenticationService); else list it with the reason.")
            .isEmpty();
        assertThat(found.keySet())
            .as("A listed site no longer writes SIGNED: delete its entry")
            .containsAll(NOT_A_SIGNATURE.keySet());
        assertThat(found).containsEntry("service/PrescriptionServiceImpl.java", 1);
    }

    @Test
    void theSignatureReAuthenticatesAControlledDrugBeforeSigning() throws IOException {
        String text = Files.readString(MAIN.resolve("service/PrescriptionServiceImpl.java"));
        int reauth = text.indexOf("prescriberReauthentication.requireForControlledSigning(");
        int controlled = text.indexOf("controlledResolver.isControlled(prescription)");
        int signed = text.indexOf(SIGNED_WRITE);
        assertThat(controlled).as("the catalogue-or-tick rule decides").isPositive();
        assertThat(reauth).as("the signature re-authenticates").isGreaterThan(controlled);
        assertThat(signed).as("before the status becomes SIGNED").isGreaterThan(reauth);
    }

    /**
     * QA D1096 (round 19): the signature is also where the paediatric dose
     * review is enforced - before the re-authentication (no code spent on a
     * refusal) and before the status becomes SIGNED. Every other SIGNED
     * writer above re-signs an order that already passed this signature.
     */
    @Test
    void theSignatureRequiresTheRecordedDoseReviewBeforeReAuthenticatingOrSigning() throws IOException {
        String text = Files.readString(MAIN.resolve("service/PrescriptionServiceImpl.java"));
        int review = text.indexOf("requirePediatricDoseReviewAtSigning(prescription, signingCards, signer);");
        int reauth = text.indexOf("prescriberReauthentication.requireForControlledSigning(");
        int signed = text.indexOf(SIGNED_WRITE);
        assertThat(review).as("the signature checks the paediatric dose review").isPositive();
        assertThat(reauth).as("after the review check").isGreaterThan(review);
        assertThat(signed).as("before the status becomes SIGNED").isGreaterThan(review);
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
