package com.example.hms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1184 (round 22): the dev volume was mounted at "/data/uploads " (a
 * trailing space) while APP_UPLOAD_DIR said /data/uploads, so every stored
 * file lived on the container's disk until the next deploy. The startup check
 * names that, and the other ways a storage directory is not persistent.
 */
@DisplayName("Upload storage persistence check (QA D1184)")
class UploadStoragePersistenceCheckTest {

    private static final String MOUNTINFO_ROOT =
        "1203 1050 0:301 / / rw,relatime master:1 - overlay overlay rw,lowerdir=/x";

    @Test
    @DisplayName("the dev shape: a volume mount path with a trailing space is not the upload directory")
    void trailingSpaceMountIsNamed() {
        List<String> warnings = UploadStoragePersistenceCheck.assess(
            "/data/uploads", "/data/uploads ", true, List.of());

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("is not on the Railway volume mounted at '/data/uploads '")
            .contains("leading or trailing whitespace");
    }

    @Test
    @DisplayName("an upload directory under the volume mount is quiet")
    void directoryUnderTheVolumeIsQuiet() {
        assertThat(UploadStoragePersistenceCheck.assess("/data/uploads", "/data/uploads", true, List.of())).isEmpty();
        assertThat(UploadStoragePersistenceCheck.assess("/data/uploads/patient-photos", "/data/uploads", true,
            List.of())).isEmpty();
    }

    @Test
    @DisplayName("on Railway with no volume at all, the directory is named")
    void noVolumeOnRailwayIsNamed() {
        assertThat(UploadStoragePersistenceCheck.assess("/app/uploads", null, true, List.of()))
            .singleElement().asString().contains("no Railway volume is attached");
        assertThat(UploadStoragePersistenceCheck.assess("uploads", null, false, List.of()))
            .as("off Railway with no mount table: nothing can be said").isEmpty();
    }

    @Test
    @DisplayName("a directory on the container's root filesystem is named; one on its own mount is not")
    void rootFilesystemIsNamed() {
        List<String> mounts = UploadStoragePersistenceCheck.parseMountInfo(List.of(
            MOUNTINFO_ROOT,
            "1290 1203 259:1 / /data/uploads\\040 rw,relatime - ext4 /dev/vdb rw"));
        assertThat(mounts).containsExactly("/", "/data/uploads ");

        assertThat(UploadStoragePersistenceCheck.assess("/data/uploads", null, false, mounts))
            .singleElement().asString().contains("container's root filesystem");
        List<String> volumeMounted = UploadStoragePersistenceCheck.parseMountInfo(List.of(
            MOUNTINFO_ROOT, "1290 1203 259:1 / /data/uploads rw,relatime - ext4 /dev/vdb rw"));
        assertThat(UploadStoragePersistenceCheck.assess("/data/uploads", null, false, volumeMounted)).isEmpty();
    }

    @Test
    @DisplayName("the kernel's octal escapes are decoded, anything else is kept")
    void octalEscapesAreDecoded() {
        assertThat(UploadStoragePersistenceCheck.unescapeOctal("/data/uploads\\040")).isEqualTo("/data/uploads ");
        assertThat(UploadStoragePersistenceCheck.unescapeOctal("/a\\011b")).isEqualTo("/a\tb");
        assertThat(UploadStoragePersistenceCheck.unescapeOctal("/a\\9z")).isEqualTo("/a\\9z");
        assertThat(UploadStoragePersistenceCheck.unescapeOctal("/a\\04")).isEqualTo("/a\\04");
    }
}
