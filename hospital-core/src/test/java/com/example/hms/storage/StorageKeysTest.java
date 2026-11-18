package com.example.hms.storage;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The one key check every adapter applies before touching a disk or a bucket. */
class StorageKeysTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "profile-images/u_profile_1_abc.jpg",
        "patient-documents/a_b-c_123e4567-e89b-12d3-a456-426614174000.pdf",
        "fhir-bulk-exports/123e4567-e89b-12d3-a456-426614174000/Patient.ndjson",
        "patient-photos/x.photo.png"
    })
    void plainRelativeKeysAreAccepted(String key) {
        assertThat(StorageKeys.requireValid(key)).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "/etc/passwd",
        "../secrets.txt",
        "patient-documents/../secrets.txt",
        "patient-documents/..",
        "patient-documents/./x.pdf",
        "patient-documents//x.pdf",
        "patient-documents/",
        "patient-documents\\..\\x.pdf",
        "C:/Windows/win.ini",
        "c:x.pdf",
        "patient-documents/x\u0000.pdf",
        "patient-documents/x\n.pdf"
    })
    void traversalAndAbsoluteFormsAreRefused(String key) {
        assertThatThrownBy(() -> StorageKeys.requireValid(key))
            .isInstanceOf(IllegalArgumentException.class)
            // The refusal never repeats the key: it can carry a patient's file name.
            .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("secrets").doesNotContain("passwd"));
    }

    @org.junit.jupiter.api.Test
    void nullAndOverlongKeysAreRefused() {
        assertThatThrownBy(() -> StorageKeys.requireValid(null)).isInstanceOf(IllegalArgumentException.class);
        String overlong = "a/" + "x".repeat(1100);
        assertThatThrownBy(() -> StorageKeys.requireValid(overlong))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
