package com.example.hms.controller;

import com.example.hms.storage.LocalFileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Avatars at the URL they always had, now read from the file store. Only
 * {@code profile-images/} is reachable, only image names, nothing else of the
 * store (patient documents sit one directory over).
 */
class ProfileImageControllerTest {

    @TempDir
    Path tempDir;

    private ProfileImageController controller;

    @BeforeEach
    void setUp() throws Exception {
        Path uploads = tempDir.resolve("uploads");
        Files.createDirectories(uploads.resolve("profile-images"));
        Files.createDirectories(uploads.resolve("patient-documents"));
        Files.writeString(uploads.resolve("profile-images").resolve("u_profile_1_abc.PNG"), "png-bytes");
        Files.writeString(uploads.resolve("patient-documents").resolve("secret.png"), "phi");
        controller = new ProfileImageController(new LocalFileStorage(uploads));
    }

    @Test
    void servesAStoredAvatarWithItsTypeAndAnHourOfCache() throws Exception {
        ResponseEntity<Resource> response = controller.profileImage("u_profile_1_abc.PNG");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).hasToString("image/png");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("max-age=3600");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().contentLength()).isEqualTo(9);
        try (var in = response.getBody().getInputStream()) {
            assertThat(new String(in.readAllBytes())).isEqualTo("png-bytes");
        }
    }

    @Test
    void aMissingAvatarIsNotFound() throws Exception {
        assertThat(controller.profileImage("gone.png").getStatusCode().value()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "..",
        "..%2Fpatient-documents%2Fsecret.png",
        "../patient-documents/secret.png",
        "..\\patient-documents\\secret.png",
        ".hidden.png",
        "a..png",
        "notes.pdf",
        "script.svg",
        "noextension"
    })
    void anythingButAPlainImageNameIsNotFound(String name) throws Exception {
        assertThat(controller.profileImage(name).getStatusCode().value()).isEqualTo(404);
    }
}
