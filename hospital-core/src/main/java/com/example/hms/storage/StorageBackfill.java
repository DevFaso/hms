package com.example.hms.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The cut-over copy from the old upload volume into the bucket
 * (docs/runbooks/object-storage.md). Runs only when
 * {@code app.storage.backfill-on-startup=true} (env
 * {@code APP_STORAGE_BACKFILL_ON_STARTUP}) and the storage is the bucket with
 * the old directory still present, on its own thread once the application is
 * ready, so a large volume never delays the health check.
 *
 * <p>Idempotent: a key the bucket already has is skipped, so a rerun (or two
 * instances during an overlapping deploy) copies only what is missing. It
 * never deletes or changes a local file. It logs three counts (copied,
 * skipped, failed) and never a file name, which can carry a patient's words.
 */
@Component
public class StorageBackfill {

    private static final Logger log = LoggerFactory.getLogger(StorageBackfill.class);

    private final FileStorage storage;
    private final boolean enabled;

    public StorageBackfill(FileStorage storage,
                           @Value("${app.storage.backfill-on-startup:false}") boolean enabled) {
        this.storage = storage;
        this.enabled = enabled;
    }

    /** The outcome of one pass. */
    public record Result(int copied, int skipped, int failed) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWhenRequested() {
        if (!enabled) {
            return;
        }
        if (!(storage instanceof S3FileStorage s3) || s3.fallback() == null) {
            log.warn("Storage backfill requested but there is nothing to copy: it needs app.storage.type=s3 "
                + "and the old upload directory (APP_UPLOAD_DIR) still present");
            return;
        }
        Thread worker = new Thread(() -> run(s3.fallback(), s3), "storage-backfill");
        worker.setDaemon(true);
        worker.start();
    }

    /** Copy every file under {@code source} whose key {@code target} lacks. Package-visible for tests. */
    static Result run(LocalFileStorage source, FileStorage target) {
        log.info("Storage backfill started: {} -> {}", source.describe(), target.describe());
        int copied = 0;
        int skipped = 0;
        int failed = 0;
        Path root = source.root();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).toList();
        } catch (IOException | RuntimeException ex) {
            log.error("Storage backfill could not list the upload directory: {}", ex.getClass().getSimpleName());
            return new Result(0, 0, 1);
        }
        for (Path file : files) {
            try {
                String key = StorageKeys.requireValid(root.relativize(file).toString().replace('\\', '/'));
                if (target.exists(key)) {
                    skipped++;
                    continue;
                }
                try (InputStream in = Files.newInputStream(file)) {
                    target.put(key, in, Files.size(file), contentTypeOf(key));
                }
                copied++;
            } catch (IOException | RuntimeException ex) {
                failed++;
                log.warn("Storage backfill could not copy one file: {}", ex.getClass().getSimpleName());
            }
        }
        Result result = new Result(copied, skipped, failed);
        log.info("Storage backfill finished: {} copied, {} already in the bucket, {} failed (of {} local files)",
            copied, skipped, failed, files.size());
        return result;
    }

    private static String contentTypeOf(String key) {
        String guessed = URLConnection.guessContentTypeFromName(key);
        return guessed != null ? guessed : "application/octet-stream";
    }
}
