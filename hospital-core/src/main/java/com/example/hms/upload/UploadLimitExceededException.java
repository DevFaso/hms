package com.example.hms.upload;

import com.example.hms.utility.MessageUtil;

/**
 * The caller has sent too many files, or too many bytes, for now: the upload
 * rate, or the daily allowance of the user or of the hospital (429, in the
 * caller's language).
 */
public class UploadLimitExceededException extends RuntimeException {

    private final String messageKey;
    private final long retryAfterSeconds;

    public UploadLimitExceededException(String messageKey, long retryAfterSeconds, Object... args) {
        super(MessageUtil.resolveOrRaw(messageKey, args));
        this.messageKey = messageKey;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getMessageKey() {
        return messageKey;
    }

    /** When trying again can succeed, in seconds; zero when no moment can be named. */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
