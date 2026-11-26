package com.example.hms.upload;

/**
 * The scanner used while none is plugged in: it reads nothing and answers
 * {@link Verdict#CLEAN}. Its verdicts are recorded under the engine
 * {@value #ENGINE}, so the files it let through can be found and checked once
 * a real scanner replaces it.
 */
public class NoOpUploadScanner implements UploadScanner {

    public static final String ENGINE = "none";

    @Override
    public String engine() {
        return ENGINE;
    }

    @Override
    public Verdict scan(Upload upload) {
        return Verdict.CLEAN;
    }
}
