package com.example.hms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * QA D1184 (round 22): patient documents stored before a deploy answered 404,
 * because the directory the files were written to was not on the persistent
 * volume. On Railway dev the volume was mounted at {@code "/data/uploads "}
 * (with a trailing space) while {@code APP_UPLOAD_DIR} said
 * {@code /data/uploads}: the application wrote to the container's own disk,
 * which is replaced at every deploy, and nothing said so.
 *
 * <p>At startup, every directory that keeps uploaded files (the upload root
 * and the patient-photo directory) is checked, and a WARN names the problem
 * when it is detectably not persistent:
 * <ul>
 *   <li>Railway reports a volume ({@code RAILWAY_VOLUME_MOUNT_PATH}) and the
 *       directory is not under it — including a mount path that differs only
 *       by surrounding whitespace;</li>
 *   <li>the service runs on Railway with no volume attached at all;</li>
 *   <li>on Linux, {@code /proc/self/mountinfo} shows that the directory sits
 *       on the container's root filesystem, not on a mount of its own.</li>
 * </ul>
 * The check never fails the startup: a missing volume loses files, a refused
 * startup loses the whole service. It only reads the environment and the
 * mount table, and logs no file names (they can carry a patient's words).
 */
@Component
public class UploadStoragePersistenceCheck {

    private static final Logger log = LoggerFactory.getLogger(UploadStoragePersistenceCheck.class);

    static final String VOLUME_MOUNT_ENV = "RAILWAY_VOLUME_MOUNT_PATH";
    static final String RAILWAY_ENV = "RAILWAY_ENVIRONMENT";
    private static final Path MOUNT_INFO = Paths.get("/proc/self/mountinfo");
    private static final String ROOT = "/";

    private final Environment env;

    public UploadStoragePersistenceCheck(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnWhenStorageIsNotPersistent() {
        String uploadDir = env.getProperty("app.upload.dir", "uploads");
        String photoDir = env.getProperty("app.patient-photo.dir", uploadDir + "/patient-photos");
        // The root-filesystem rule only means something in a container: on a
        // developer's machine or a CI host the root filesystem is persistent.
        List<String> mountPoints = runsInAContainer() ? readMountPoints() : List.of();
        String volumeMount = env.getProperty(VOLUME_MOUNT_ENV);
        boolean onRailway = env.getProperty(RAILWAY_ENV) != null;
        for (String dir : List.of(uploadDir, photoDir)) {
            // Always stated, so the path can be compared with the volume from the logs alone.
            log.info("Upload storage directory: '{}' (Railway volume mount: {})",
                absolute(dir), volumeMount == null ? "none reported" : "'" + volumeMount + "'");
            for (String warning : assess(dir, volumeMount, onRailway, mountPoints)) {
                log.warn("Upload storage NOT persistent (QA D1184): {}", warning);
            }
        }
    }

    /**
     * The reasons {@code directory} is detectably not persistent; empty when
     * nothing says so. {@code mountPoints} is empty when the mount table could
     * not be read (not Linux), and then only the Railway variables are used.
     */
    static List<String> assess(String directory, String volumeMount, boolean onRailway, List<String> mountPoints) {
        List<String> warnings = new ArrayList<>();
        Path dir;
        try {
            dir = realOrNormalized(Paths.get(directory).toAbsolutePath().normalize());
        } catch (InvalidPathException ex) {
            warnings.add("the storage directory '" + directory + "' is not a valid path");
            return warnings;
        }
        if (volumeMount != null && !volumeMount.isEmpty()) {
            if (!isUnder(dir, volumeMount)) {
                String hint = volumeMount.equals(volumeMount.strip())
                    ? ""
                    : " (the volume's mount path has leading or trailing whitespace: remove it in the Railway volume settings)";
                warnings.add("'" + dir + "' is not on the Railway volume mounted at '" + volumeMount + "'" + hint
                    + "; files written there are lost at the next deploy");
            }
        } else if (onRailway) {
            warnings.add("no Railway volume is attached to this service; files written to '" + dir
                + "' are lost at the next deploy (attach a volume and point APP_UPLOAD_DIR at its mount path)");
        }
        String mount = containingMount(dir, mountPoints);
        if (ROOT.equals(mount)) {
            warnings.add("'" + dir + "' is on the container's root filesystem, not on a mounted volume");
        }
        return warnings;
    }

    /** The real path when the directory exists (a symlink onto a volume is on the volume), else as written. */
    static Path realOrNormalized(Path dir) {
        if (!Files.exists(dir)) {
            return dir;
        }
        try {
            return dir.toRealPath();
        } catch (IOException ex) {
            return dir;
        }
    }

    static String absolute(String directory) {
        try {
            return Paths.get(directory).toAbsolutePath().normalize().toString();
        } catch (InvalidPathException ex) {
            return directory;
        }
    }

    /** Whether {@code dir} is {@code mount} or below it, the mount path taken exactly as written. */
    static boolean isUnder(Path dir, String mount) {
        try {
            return dir.startsWith(Paths.get(mount).toAbsolutePath().normalize());
        } catch (InvalidPathException ex) {
            return false;
        }
    }

    /** The longest mount point that contains {@code dir}; null when the table is empty. */
    static String containingMount(Path dir, List<String> mountPoints) {
        String best = null;
        for (String mountPoint : mountPoints) {
            if (isUnder(dir, mountPoint) && (best == null || mountPoint.length() > best.length())) {
                best = mountPoint;
            }
        }
        return best;
    }

    private boolean runsInAContainer() {
        return env.getProperty(RAILWAY_ENV) != null || Files.exists(Paths.get("/.dockerenv"));
    }

    private static List<String> readMountPoints() {
        if (!Files.isReadable(MOUNT_INFO)) {
            return List.of();
        }
        try {
            return parseMountInfo(Files.readAllLines(MOUNT_INFO, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            log.debug("Mount table not readable: {}", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * The mount points of {@code /proc/self/mountinfo} lines: the fifth field,
     * with the kernel's octal escapes ({@code \040} for a space) decoded.
     */
    static List<String> parseMountInfo(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String[] fields = line.split(" ");
            if (fields.length > 4) {
                out.add(unescapeOctal(fields[4]));
            }
        }
        return out;
    }

    static String unescapeOctal(String field) {
        StringBuilder sb = new StringBuilder(field.length());
        int i = 0;
        while (i < field.length()) {
            char c = field.charAt(i);
            if (c == '\\' && isOctal(field, i + 1)) {
                sb.append((char) Integer.parseInt(field.substring(i + 1, i + 4), 8));
                i += 4;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean isOctal(String s, int from) {
        if (from + 3 > s.length()) {
            return false;
        }
        for (int k = from; k < from + 3; k++) {
            char d = s.charAt(k);
            if (d < '0' || d > '7') {
                return false;
            }
        }
        return true;
    }
}
