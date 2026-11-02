package com.example.hms.exception;

import com.example.hms.utility.MessageUtil;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * QA D1299 / D1300 (round 28): the one way a service refuses missing request
 * values. Every check of the body is made first, then ONE
 * {@link FieldBusinessException} names every field that failed, each with its
 * translated reason, so an empty body (`{}`) is answered once with every
 * missing field marked. Before, each service threw at its first check: the
 * chat named the message but never the recipient, the registration answered
 * with no field at all, the invoice named the patient only on the second press.
 *
 * <pre>{@code
 * RequiredFields.check()
 *     .require(hasContent || hasAttachments, "content", "chat.message.empty")
 *     .require(dto.getRecipientId() != null || hasText(dto.getRecipientEmail()), "recipientId", "chat.recipient.required")
 *     .orThrow();
 * }</pre>
 */
public final class RequiredFields {

    private final Map<String, String> missing = new LinkedHashMap<>();
    /** The key and arguments of each refusal, so a single one keeps its message key. */
    private final Map<String, Object[]> keys = new LinkedHashMap<>();

    private RequiredFields() {
    }

    public static RequiredFields check() {
        return new RequiredFields();
    }

    /** Records {@code field} as refused with the reason {@code messageKey} when {@code present} is false. */
    public RequiredFields require(boolean present, String field, String messageKey, Object... args) {
        if (!present && !missing.containsKey(field)) {
            missing.put(field, MessageUtil.resolveOrRaw(messageKey, args));
            Object[] keyAndArgs = new Object[args.length + 1];
            keyAndArgs[0] = messageKey;
            System.arraycopy(args, 0, keyAndArgs, 1, args.length);
            keys.put(field, keyAndArgs);
        }
        return this;
    }

    /** A value that must be given: not null, not blank text, not an empty collection. */
    public RequiredFields requireValue(Object value, String field, String messageKey, Object... args) {
        return require(isGiven(value), field, messageKey, args);
    }

    /** True when nothing was refused so far. */
    public boolean isEmpty() {
        return missing.isEmpty();
    }

    /** Throws one refusal naming every recorded field; does nothing when all were given. */
    public void orThrow() {
        if (missing.size() == 1) {
            // One field: the usual single-field refusal, with its message key.
            Map.Entry<String, Object[]> only = keys.entrySet().iterator().next();
            Object[] keyAndArgs = only.getValue();
            throw new FieldBusinessException(only.getKey(), (String) keyAndArgs[0],
                java.util.Arrays.copyOfRange(keyAndArgs, 1, keyAndArgs.length));
        }
        if (!missing.isEmpty()) {
            throw new FieldBusinessException(missing);
        }
    }

    static boolean isGiven(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof CharSequence text) {
            return !text.toString().isBlank();
        }
        if (value instanceof Collection<?> items) {
            return !items.isEmpty();
        }
        return true;
    }
}
