package com.example.hms.service.notification;

import java.util.UUID;

/** Carries recipient ownership to the durable mail queue; always cleared on the composing thread. */
public final class VerifiedPatientMailScope {
    private VerifiedPatientMailScope() {}
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    public record Context(UUID patientId, String recipient) {
        @Override public String toString() { return "VerifiedPatientMailScope"; }
    }
    public static Context current() { return CURRENT.get(); }
    static void run(UUID patientId, String recipient, Runnable action) {
        Context previous = CURRENT.get();
        try { CURRENT.set(new Context(patientId, recipient)); action.run(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
