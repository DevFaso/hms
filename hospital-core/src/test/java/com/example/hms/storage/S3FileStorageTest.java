package com.example.hms.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Error mapping of the S3 adapter (the happy paths run against S3Mock in
 * {@link S3FileStorageIT}): a 404 is "not there", anything else is an
 * {@link IOException} that names the operation and never the key.
 */
class S3FileStorageTest {

    private final S3Client client = mock(S3Client.class);
    private final S3FileStorage storage = new S3FileStorage(client, "bucket", null);

    private static S3Exception status(int code) {
        return (S3Exception) S3Exception.builder().statusCode(code).message("refused").build();
    }

    @Test
    void aNotFoundStatusIsAMissNotAnError() throws IOException {
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(status(404));
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(status(404));

        assertThat(storage.get("patient-documents/secret-name.pdf")).isEmpty();
        assertThat(storage.exists("patient-documents/secret-name.pdf")).isFalse();
    }

    @Test
    void noSuchKeyIsAMiss() throws IOException {
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());

        assertThat(storage.get("a/b")).isEmpty();
        assertThat(storage.exists("a/b")).isFalse();
    }

    @Test
    void otherFailuresAreIoExceptionsThatNeverNameTheKey() {
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(status(403));
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(status(500));
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenThrow(SdkClientException.create("connection reset"));
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(status(403));

        assertThatThrownBy(() -> storage.get("patient-documents/secret-name.pdf"))
            .isInstanceOf(IOException.class).hasMessageContaining("get").hasMessageContaining("403")
            .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("secret-name"));
        assertThatThrownBy(() -> storage.exists("patient-documents/secret-name.pdf"))
            .isInstanceOf(IOException.class).hasMessageContaining("head");
        assertThatThrownBy(() -> storage.put("patient-documents/secret-name.pdf",
                new ByteArrayInputStream(new byte[] {1}), 1, null))
            .isInstanceOf(IOException.class).hasMessageContaining("put")
            .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("secret-name"));
        assertThatThrownBy(() -> storage.delete("patient-documents/secret-name.pdf"))
            .isInstanceOf(IOException.class).hasMessageContaining("delete");
    }

    @Test
    void anInvalidKeyNeverReachesTheBucket() {
        assertThatThrownBy(() -> storage.get("../x")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void describeNamesTheBucket() {
        assertThat(storage.describe()).contains("bucket");
    }
}
