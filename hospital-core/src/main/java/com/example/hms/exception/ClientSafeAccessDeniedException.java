package com.example.hms.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * A 403 whose message is written for the caller and may be shown to them.
 *
 * <p>{@code GlobalExceptionHandler.handleAccessDenied} answers every other
 * {@link AccessDeniedException} with the literal "Access denied", and that
 * stays the default on purpose: an access-denied message composed deep in a
 * service can say "this record belongs to another hospital", which turns a
 * refusal into an existence oracle across tenants (the 404-not-403 policy in
 * the {@code multi-tenancy-scoping} skill). Blanket-exposing messages would
 * make every such sentence a leak the moment someone wrote one.
 *
 * <p>So exposure is opt-in, per throw site. Throw this only when:
 * <ul>
 *   <li>the entity the refusal is about has ALREADY been resolved within the
 *       caller's own scope (a foreign one answered 404 before this point), so
 *       the refusal tells the caller nothing about anything they could not
 *       already read; and</li>
 *   <li>the message names what the CALLER would need to be or hold — a role,
 *       an assignment, being the prescriber — never a fact about the record or
 *       another person that the caller could not see.</li>
 * </ul>
 * A {@code @PreAuthorize} refusal is never one of these and keeps the default.
 */
public class ClientSafeAccessDeniedException extends AccessDeniedException {

    public ClientSafeAccessDeniedException(String message) {
        super(message);
    }
}
