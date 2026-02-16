package com.example.hms.payload.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmailInvoiceRequestTest {

    // ─── Canonical constructor ───────────────────────────────────

    @Test
    void fullConstructor() {
        List<String> to = List.of("a@b.com");
        List<String> cc = List.of("c@d.com");
        List<String> bcc = List.of("e@f.com");
        EmailInvoiceRequest r = new EmailInvoiceRequest(to, cc, bcc, "Hello", "en", true);

        assertThat(r.to()).isEqualTo(to);
        assertThat(r.cc()).isEqualTo(cc);
        assertThat(r.bcc()).isEqualTo(bcc);
        assertThat(r.message()).isEqualTo("Hello");
        assertThat(r.locale()).isEqualTo("en");
        assertThat(r.attachPdf()).isTrue();
    }

    @Test
    void constructorWithNulls() {
        EmailInvoiceRequest r = new EmailInvoiceRequest(List.of("x@y.com"), null, null, null, null, false);

        assertThat(r.to()).containsExactly("x@y.com");
        assertThat(r.cc()).isNull();
        assertThat(r.bcc()).isNull();
        assertThat(r.message()).isNull();
        assertThat(r.locale()).isNull();
        assertThat(r.attachPdf()).isFalse();
    }

    // ─── Accessors ───────────────────────────────────────────────

    @Test
    void toAccessor() {
        EmailInvoiceRequest r = new EmailInvoiceRequest(List.of("a@b.com", "c@d.com"), null, null, null, null, false);
        assertThat(r.to()).hasSize(2);
    }

    @Test
    void ccAccessor() {
        List<String> cc = List.of("cc@cc.com");
        EmailInvoiceRequest r = new EmailInvoiceRequest(List.of("a@b.com"), cc, null, null, null, false);
        assertThat(r.cc()).isEqualTo(cc);
    }

    @Test
    void bccAccessor() {
        List<String> bcc = List.of("bcc@bcc.com");
        EmailInvoiceRequest r = new EmailInvoiceRequest(List.of("a@b.com"), null, bcc, null, null, false);
        assertThat(r.bcc()).isEqualTo(bcc);
    }

    @Test
    void messageAccessor() {
        EmailInvoiceRequest r = new EmailInvoiceRequest(List.of("a@b.com"), null, null, "msg", null, false);
        assertThat(r.message()).isEqualTo("msg");
    }

    @Test
    void localeAccessor() {
        EmailInvoiceRequest r = new EmailInvoiceRequest(List.of("a@b.com"), null, null, null, "fr", false);
        assertThat(r.locale()).isEqualTo("fr");
    }

    @Test
    void attachPdfAccessor() {
        EmailInvoiceRequest r = new EmailInvoiceRequest(List.of("a@b.com"), null, null, null, null, true);
        assertThat(r.attachPdf()).isTrue();
    }

    // ─── equals ──────────────────────────────────────────────────

    @Test
    void equalsSameValues() {
        List<String> to = List.of("a@b.com");
        EmailInvoiceRequest r1 = new EmailInvoiceRequest(to, null, null, "m", "en", true);
        EmailInvoiceRequest r2 = new EmailInvoiceRequest(to, null, null, "m", "en", true);
        assertThat(r1).isEqualTo(r2);
    }

    @Test
    void equalsReflexive() {
        EmailInvoiceRequest r = new EmailInvoiceRequest(List.of("a@b.com"), null, null, null, null, false);
        assertThat(r).isEqualTo(r);
    }

    @Test
    void notEqualDifferentTo() {
        EmailInvoiceRequest r1 = new EmailInvoiceRequest(List.of("a@b.com"), null, null, null, null, false);
        EmailInvoiceRequest r2 = new EmailInvoiceRequest(List.of("x@y.com"), null, null, null, null, false);
        assertThat(r1).isNotEqualTo(r2);
    }

    @Test
    void notEqualDifferentMessage() {
        EmailInvoiceRequest r1 = new EmailInvoiceRequest(List.of("a@b.com"), null, null, "m1", null, false);
        EmailInvoiceRequest r2 = new EmailInvoiceRequest(List.of("a@b.com"), null, null, "m2", null, false);
        assertThat(r1).isNotEqualTo(r2);
    }

    @Test
    void notEqualDifferentAttachPdf() {
        EmailInvoiceRequest r1 = new EmailInvoiceRequest(List.of("a@b.com"), null, null, null, null, true);
        EmailInvoiceRequest r2 = new EmailInvoiceRequest(List.of("a@b.com"), null, null, null, null, false);
        assertThat(r1).isNotEqualTo(r2);
    }

    @Test
    void notEqualToNull() {
        EmailInvoiceRequest r = new EmailInvoiceRequest(List.of("a@b.com"), null, null, null, null, false);
        assertThat(r).isNotEqualTo(null);
    }

    @Test
    void notEqualToDifferentType() {
        EmailInvoiceRequest r = new EmailInvoiceRequest(List.of("a@b.com"), null, null, null, null, false);
        assertThat(r).isNotEqualTo("string");
    }

    // ─── hashCode ────────────────────────────────────────────────

    @Test
    void hashCodeSameForEqualObjects() {
        List<String> to = List.of("a@b.com");
        EmailInvoiceRequest r1 = new EmailInvoiceRequest(to, null, null, "m", "en", true);
        EmailInvoiceRequest r2 = new EmailInvoiceRequest(to, null, null, "m", "en", true);
        assertThat(r1).hasSameHashCodeAs(r2);
    }

    @Test
    void hashCodeDiffersForDifferentObjects() {
        EmailInvoiceRequest r1 = new EmailInvoiceRequest(List.of("a@b.com"), null, null, null, null, true);
        EmailInvoiceRequest r2 = new EmailInvoiceRequest(List.of("x@y.com"), null, null, null, null, false);
        assertThat(r1.hashCode()).isNotEqualTo(r2.hashCode());
    }

    // ─── toString ────────────────────────────────────────────────

    @Test
    void toStringContainsFieldNames() {
        EmailInvoiceRequest r = new EmailInvoiceRequest(List.of("a@b.com"), null, null, "msg", "en", true);
        String s = r.toString();
        assertThat(s).contains("a@b.com")
            .contains("msg")
            .contains("en")
            .contains("true");
    }
}
