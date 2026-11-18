package com.example.hms.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * {@link FileStorage} on a local directory (the upload root,
 * {@code app.upload.dir}): the behaviour hospital-core always had, kept as the
 * default for local development and tests, and as the read-through fallback of
 * {@link S3FileStorage} while a deployment still has its old volume attached.
 *
 * <p>This class and {@link StorageBackfill} are the only code that writes or
 * deletes files under the upload root ({@code UploadStorageWriteGuardTest}).
 */
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    @Override
    public void put(String key, InputStream content, long length, String contentType) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public Optional<StoredObject> get(String key) throws IOException {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(new StoredObject(Files.newInputStream(path), Files.size(path)));
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    @Override
    public String describe() {
        return "local directory '" + root + "'";
    }

    /** The file of a valid key; the containment check is a second fence behind {@link StorageKeys}. */
    Path resolve(String key) {
        Path path = root.resolve(StorageKeys.requireValid(key)).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Storage key resolves outside the storage root");
        }
        return path;
    }
}
