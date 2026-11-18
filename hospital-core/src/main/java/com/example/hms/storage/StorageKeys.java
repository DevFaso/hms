package com.example.hms.storage;

/**
 * The single check on a storage key, applied by every {@link FileStorage}
 * adapter before it touches a disk or a bucket. A key is a relative path of
 * plain segments separated by {@code '/'}: no {@code ".."} or {@code "."}
 * segment, no leading slash, no backslash, no drive letter or scheme
 * ({@code ':'}), no empty segment, no control character. Anything else is
 * refused, so a key built from a stored reference can never name a file
 * outside the store.
 */
public final class StorageKeys {

    private static final int MAX_KEY_LENGTH = 1024;

    /**
     * The refusal is a message key ("the file path is not valid", localized by
     * the global handler should it ever reach a reader); it never repeats the
     * key, which can carry a patient's file name.
     */
    static final String INVALID = "file.path.invalid";

    private StorageKeys() {
    }

    /**
     * @return {@code key} unchanged when it is a valid relative key
     * @throws IllegalArgumentException otherwise, with the message key {@value #INVALID}
     */
    public static String requireValid(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException(INVALID);
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(INVALID);
        }
        if (key.startsWith("/")) {
            throw new IllegalArgumentException(INVALID);
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '\\' || c == ':' || c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException(INVALID);
            }
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(INVALID);
            }
        }
        return key;
    }
}
