package com.example.hms.utility;

import com.example.hms.payload.dto.NotificationDeliveryStatusDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects activation-notification delivery outcomes across the layers of a
 * single registration / resend request so the controller can attach them to
 * the response the REGISTRAR sees.
 *
 * <p>Why a ThreadLocal: the confirmation email/SMS go out from an
 * {@code AFTER_COMMIT} {@code @TransactionalEventListener}, which Spring runs
 * on the request thread <em>after</em> the service-layer transaction commits
 * but <em>before</em> the controller resumes. No return value or event field
 * can cross that boundary without rewriting the whole event chain; a
 * thread-local armed by the controller can.</p>
 *
 * <p>Leak safety: {@link #report} is a no-op unless the request explicitly
 * {@link #open()}ed collection, and {@link #close()} always removes the
 * thread-local — so flows that never arm it (bulk import, background jobs)
 * cannot bleed outcomes into a later request served by the same pooled
 * thread. Controllers must call {@code close()} in a {@code finally}.</p>
 */
public final class ActivationDeliveryTracker {

    private static final ThreadLocal<List<NotificationDeliveryStatusDTO>> COLLECTED =
        new ThreadLocal<>();

    private ActivationDeliveryTracker() {
    }

    /** Arm collection for the current request thread. */
    public static void open() {
        COLLECTED.set(new ArrayList<>());
    }

    /** Report one delivery outcome; silently ignored when not armed. */
    public static void report(NotificationDeliveryStatusDTO status) {
        List<NotificationDeliveryStatusDTO> list = COLLECTED.get();
        if (list != null && status != null) {
            list.add(status);
        }
    }

    /** Drain everything recorded and disarm. Never null; idempotent. */
    public static List<NotificationDeliveryStatusDTO> close() {
        List<NotificationDeliveryStatusDTO> list = COLLECTED.get();
        COLLECTED.remove();
        return list != null ? List.copyOf(list) : List.of();
    }

    /** {@code jdoe@hospital.com} → {@code j***@hospital.com}. */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** {@code +22670123456} → {@code +226*****56}. */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String trimmed = phone.trim();
        if (trimmed.length() <= 4) {
            return "***";
        }
        String prefix = trimmed.substring(0, Math.min(4, trimmed.length() - 2));
        String suffix = trimmed.substring(trimmed.length() - 2);
        return prefix + "*****" + suffix;
    }
}
