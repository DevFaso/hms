package com.example.hms.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What every {@link FileStorage} adapter promises, run against each one
 * ({@link LocalFileStorageTest}, {@link S3FileStorageIT}).
 */
abstract class FileStorageContractTest {

    /** The adapter under test, empty. */
    protected abstract FileStorage storage();

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void put(String key, String text) throws IOException {
        byte[] content = bytes(text);
        storage().put(key, new ByteArrayInputStream(content), content.length, "text/plain");
    }

    private String read(String key) throws IOException {
        Optional<StoredObject> stored = storage().get(key);
        assertThat(stored).isPresent();
        try (StoredObject object = stored.get()) {
            byte[] content = object.content().readAllBytes();
            assertThat(object.length()).isEqualTo(content.length);
            return new String(content, StandardCharsets.UTF_8);
        }
    }

    @Test
    void whatIsPutIsReadBackWithItsLength() throws IOException {
        put("patient-documents/a.txt", "hello");

        assertThat(read("patient-documents/a.txt")).isEqualTo("hello");
        assertThat(storage().exists("patient-documents/a.txt")).isTrue();
    }

    @Test
    void aSecondPutReplacesTheFirst() throws IOException {
        put("chat-attachments/b.txt", "first");
        put("chat-attachments/b.txt", "second, longer");

        assertThat(read("chat-attachments/b.txt")).isEqualTo("second, longer");
    }

    @Test
    void aMissingKeyIsEmptyNotAnError() throws IOException {
        assertThat(storage().get("patient-documents/none.txt")).isEmpty();
        assertThat(storage().exists("patient-documents/none.txt")).isFalse();
    }

    @Test
    void deleteRemovesAndIsQuietWhenNothingIsThere() throws IOException {
        put("profile-images/c.txt", "x");

        storage().delete("profile-images/c.txt");
        storage().delete("profile-images/c.txt");

        assertThat(storage().get("profile-images/c.txt")).isEmpty();
    }

    @Test
    void nestedKeysWork() throws IOException {
        put("fhir-bulk-exports/job-1/Patient.ndjson", "{}\n");

        assertThat(read("fhir-bulk-exports/job-1/Patient.ndjson")).isEqualTo("{}\n");
    }

    @Test
    void everyOperationRefusesATraversalKey() {
        FileStorage store = storage();
        ByteArrayInputStream content = new ByteArrayInputStream(bytes("x"));
        assertThatThrownBy(() -> store.put("../x.txt", content, 1, "text/plain"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.get("patient-documents/../../x.txt"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.exists("/x.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.delete("a\\..\\x.txt")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void describeNamesTheStore() {
        assertThat(storage().describe()).isNotBlank();
    }
}
