package com.example.hms.service.support;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D1537 (round 40): whether an order was ever issued, by its signature as well as its status. */
class PrescriptionIssuanceTest {

    private static Prescription rx(PrescriptionStatus status, boolean signed) {
        Prescription p = new Prescription();
        p.setStatus(status);
        if (signed) {
            p.setSignedAt(LocalDateTime.now());
        }
        return p;
    }

    @ParameterizedTest
    @EnumSource(value = PrescriptionStatus.class, names = {"DRAFT", "PENDING_SIGNATURE"})
    void aProposalWasNeverIssued(PrescriptionStatus status) {
        assertThat(PrescriptionIssuance.wasIssued(rx(status, false))).isFalse();
        assertThat(PrescriptionIssuance.isDiscardedProposal(rx(status, false))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PrescriptionStatus.class, names = {"CANCELLED", "DISCONTINUED"})
    void aWithdrawnOrderWasIssuedOnlyWhenSigned(PrescriptionStatus status) {
        assertThat(PrescriptionIssuance.wasIssued(rx(status, false))).isFalse();
        assertThat(PrescriptionIssuance.isDiscardedProposal(rx(status, false))).isTrue();
        assertThat(PrescriptionIssuance.wasIssued(rx(status, true))).isTrue();
        assertThat(PrescriptionIssuance.isDiscardedProposal(rx(status, true))).isFalse();
    }

    @Test
    void theSignerOrTheDigestAloneIsASignature() {
        Prescription bySigner = rx(PrescriptionStatus.CANCELLED, false);
        bySigner.setSignedBy(new com.example.hms.model.Staff());
        Prescription byDigest = rx(PrescriptionStatus.DISCONTINUED, false);
        byDigest.setSignatureValue("abc");
        assertThat(PrescriptionIssuance.wasIssued(bySigner)).isTrue();
        assertThat(PrescriptionIssuance.wasIssued(byDigest)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = PrescriptionStatus.class, mode = EnumSource.Mode.EXCLUDE,
        names = {"DRAFT", "PENDING_SIGNATURE", "CANCELLED", "DISCONTINUED"})
    void everyOtherStatusIsASignedOrder(PrescriptionStatus status) {
        assertThat(PrescriptionIssuance.wasIssued(rx(status, false))).isTrue();
    }

    @Test
    void theListsKeepTheirOrder() {
        Prescription signed = rx(PrescriptionStatus.SIGNED, true);
        Prescription draft = rx(PrescriptionStatus.DRAFT, false);
        Prescription discarded = rx(PrescriptionStatus.CANCELLED, false);
        Prescription stopped = rx(PrescriptionStatus.DISCONTINUED, true);
        List<Prescription> rows = List.of(signed, draft, discarded, stopped);
        assertThat(PrescriptionIssuance.issuedOnly(rows)).containsExactly(signed, stopped);
        assertThat(PrescriptionIssuance.withoutDiscardedProposals(rows)).containsExactly(signed, draft, stopped);
        assertThat(PrescriptionIssuance.wasIssued(null)).isFalse();
    }
}
