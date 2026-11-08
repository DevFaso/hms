package com.example.hms.service.support;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1537 (round 40): whether an order was ever issued, by the fact recorded when it was withdrawn
 * (Prescription.setStatus: the status before was a proposal), never by the signature columns.
 */
class PrescriptionIssuanceTest {

    private static Prescription withdrawnFrom(PrescriptionStatus before, PrescriptionStatus withdrawnTo) {
        Prescription p = new Prescription();
        p.setStatus(before);
        p.setStatus(withdrawnTo);
        return p;
    }

    @ParameterizedTest
    @EnumSource(value = PrescriptionStatus.class, names = {"DRAFT", "PENDING_SIGNATURE"})
    void aProposalWasNeverIssued(PrescriptionStatus status) {
        Prescription p = new Prescription();
        p.setStatus(status);
        assertThat(PrescriptionIssuance.wasIssued(p)).isFalse();
        assertThat(PrescriptionIssuance.isDiscardedProposal(p)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PrescriptionStatus.class, names = {"CANCELLED", "DISCONTINUED"})
    void aProposalWithdrawnWasNeverIssued(PrescriptionStatus withdrawnTo) {
        for (PrescriptionStatus before : PrescriptionIssuance.PROPOSALS) {
            Prescription p = withdrawnFrom(before, withdrawnTo);
            assertThat(p.isWithdrawnBeforeSigning()).isTrue();
            assertThat(PrescriptionIssuance.wasIssued(p)).isFalse();
            assertThat(PrescriptionIssuance.isDiscardedProposal(p)).isTrue();
        }
    }

    /** A new order created already withdrawn (a create body asserting CANCELLED) starts as a DRAFT. */
    @Test
    void anOrderCreatedWithdrawnWasNeverIssued() {
        Prescription p = new Prescription();
        p.setStatus(PrescriptionStatus.CANCELLED);
        assertThat(PrescriptionIssuance.wasIssued(p)).isFalse();
    }

    /**
     * A signed order stopped afterwards is a past medicine, signature columns or not: an order signed
     * before V118 carries none.
     */
    @ParameterizedTest
    @EnumSource(value = PrescriptionStatus.class, mode = EnumSource.Mode.EXCLUDE,
        names = {"DRAFT", "PENDING_SIGNATURE", "CANCELLED", "DISCONTINUED"})
    void aSignedOrderWithdrawnStaysIssued(PrescriptionStatus signedState) {
        for (PrescriptionStatus withdrawnTo : List.of(PrescriptionStatus.CANCELLED, PrescriptionStatus.DISCONTINUED)) {
            Prescription p = withdrawnFrom(signedState, withdrawnTo);
            assertThat(p.getSignedAt()).isNull();
            assertThat(PrescriptionIssuance.wasIssued(p)).as(signedState + " -> " + withdrawnTo).isTrue();
            assertThat(PrescriptionIssuance.isDiscardedProposal(p)).isFalse();
        }
    }

    /** A row read from the database keeps what its withdrawal recorded (the builder writes the field, no setter). */
    @Test
    void aRowWithoutTheFactIsShown() {
        Prescription legacy = Prescription.builder().status(PrescriptionStatus.DISCONTINUED).build();
        assertThat(legacy.isWithdrawnBeforeSigning()).isFalse();
        assertThat(PrescriptionIssuance.wasIssued(legacy)).isTrue();
    }

    @Test
    void aWithdrawnOrderMovedToAnotherWithdrawnStatusKeepsItsFact() {
        Prescription p = withdrawnFrom(PrescriptionStatus.DRAFT, PrescriptionStatus.CANCELLED);
        p.setStatus(PrescriptionStatus.DISCONTINUED);
        assertThat(p.isWithdrawnBeforeSigning()).isTrue();
        Prescription signed = withdrawnFrom(PrescriptionStatus.SIGNED, PrescriptionStatus.DISCONTINUED);
        signed.setStatus(PrescriptionStatus.CANCELLED);
        assertThat(signed.isWithdrawnBeforeSigning()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PrescriptionStatus.class, mode = EnumSource.Mode.EXCLUDE,
        names = {"DRAFT", "PENDING_SIGNATURE", "CANCELLED", "DISCONTINUED"})
    void everyOtherStatusIsASignedOrder(PrescriptionStatus status) {
        Prescription p = new Prescription();
        p.setStatus(status);
        assertThat(PrescriptionIssuance.wasIssued(p)).isTrue();
    }

    @Test
    void theListsKeepTheirOrder() {
        Prescription signed = new Prescription();
        signed.setStatus(PrescriptionStatus.SIGNED);
        Prescription draft = new Prescription();
        Prescription discarded = withdrawnFrom(PrescriptionStatus.DRAFT, PrescriptionStatus.CANCELLED);
        Prescription stopped = withdrawnFrom(PrescriptionStatus.SIGNED, PrescriptionStatus.DISCONTINUED);
        List<Prescription> rows = List.of(signed, draft, discarded, stopped);
        assertThat(PrescriptionIssuance.issuedOnly(rows)).containsExactly(signed, stopped);
        assertThat(PrescriptionIssuance.withoutDiscardedProposals(rows)).containsExactly(signed, draft, stopped);
        assertThat(PrescriptionIssuance.wasIssued(null)).isFalse();
    }
}
