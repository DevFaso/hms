package com.example.hms.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** The cut-over copy: what the target lacks is copied, the source is never touched. */
class StorageBackfillTest {

    @TempDir
    Path tempDir;

    @Test
    void copiesOnlyWhatIsMissingAndNeverDeletesTheSource() throws Exception {
        Path volume = tempDir.resolve("uploads");
        Files.createDirectories(volume.resolve("chat-attachments"));
        Files.writeString(volume.resolve("chat-attachments").resolve("a.jpg"), "a");
        Files.writeString(volume.resolve("chat-attachments").resolve("b.jpg"), "b");
        LocalFileStorage target = new LocalFileStorage(tempDir.resolve("bucket"));
        byte[] b = "kept".getBytes(StandardCharsets.UTF_8);
        target.put("chat-attachments/b.jpg", new ByteArrayInputStream(b), b.length, null);

        StorageBackfill.Result result = StorageBackfill.run(new LocalFileStorage(volume), target);

        assertThat(result).isEqualTo(new StorageBackfill.Result(1, 1, 0));
        assertThat(tempDir.resolve("bucket").resolve("chat-attachments").resolve("a.jpg")).hasContent("a");
        assertThat(tempDir.resolve("bucket").resolve("chat-attachments").resolve("b.jpg")).hasContent("kept");
        assertThat(volume.resolve("chat-attachments").resolve("a.jpg")).hasContent("a");
    }

    @Test
    void aFailingCopyIsCountedAndTheRestContinues() throws Exception {
        Path volume = tempDir.resolve("uploads");
        Files.createDirectories(volume.resolve("x"));
        Files.writeString(volume.resolve("x").resolve("1.txt"), "1");
        Files.writeString(volume.resolve("x").resolve("2.txt"), "2");
        FileStorage flaky = new LocalFileStorage(tempDir.resolve("bucket")) {
            @Override
            public void put(String key, InputStream content, long length, String contentType) throws IOException {
                if (key.endsWith("1.txt")) {
                    throw new IOException("Object storage put failed");
                }
                super.put(key, content, length, contentType);
            }
        };

        assertThat(StorageBackfill.run(new LocalFileStorage(volume), flaky))
            .isEqualTo(new StorageBackfill.Result(1, 0, 1));
    }

    @Test
    void aMissingSourceIsOneFailureNotACrash() {
        assertThat(StorageBackfill.run(new LocalFileStorage(tempDir.resolve("absent")),
            new LocalFileStorage(tempDir.resolve("bucket"))))
            .isEqualTo(new StorageBackfill.Result(0, 0, 1));
    }

    @Test
    void nothingHappensUnlessRequestedAndOnTheBucket() {
        FileStorage storage = mock(FileStorage.class);

        new StorageBackfill(storage, false).startWhenRequested();
        // Requested on local storage: there is nothing to copy into, so it only warns.
        new StorageBackfill(storage, true).startWhenRequested();

        verifyNoInteractions(storage);
    }
}
