package com.example.hms.service.integration;

/**
 * Result of processing one inbound HL7 v2 message through the MLLP
 * dispatcher. The dispatcher maps each value to an HL7 v2 ACK code:
 *
 * <ul>
 *   <li>{@link #ACCEPTED}              → AA (application accept)</li>
 *   <li>{@link #REJECTED_NOT_FOUND}    → AE (application error,
 *       cannot resolve the referenced entity — an unknown placer order
 *       number, an unknown patient MRN, <em>or</em> one that exists but
 *       belongs to another hospital)</li>
 *   <li>{@link #REJECTED_INVALID}      → AE (parse failure, missing
 *       mandatory fields, etc.)</li>
 *   <li>{@link #REJECTED_NOT_OWNER}    → AR (terminal: patients the
 *       hospital holds, but an action only another hospital may take)</li>
 * </ul>
 *
 * <p><b>There is deliberately no cross-tenant outcome.</b> There used to
 * be one, mapping to AR, and it was a cross-tenant oracle: an
 * allowlisted sender that got AR for one identifier and AE for another
 * had learned that the first exists in a hospital it cannot read, and
 * could walk an identifier space to enumerate them. The ORU^R01 path
 * closed that (B13, PR #715); the ADT and A40 merge paths closed it
 * afterwards. Every "you may not touch this entity" answer is now the
 * same answer as "there is no such entity", byte for byte, and the
 * constant that used to make the two distinguishable is gone so the
 * distinction cannot be reintroduced by returning it.
 *
 * <p>Acknowledged residual: the answers are identical in content, not in
 * time. A cross-tenant refusal does more work before answering than an
 * unknown identifier does — one or two extra reads and a recorder insert —
 * so a patient attacker with a stable socket could still separate the two
 * statistically. Closing that needs constant-time handling of a database
 * path, which is a different piece of work; what is closed here is the
 * answer anyone can read off a single message.
 *
 * <p>The reason is not lost: each path records it on the
 * {@code integration_message_event} row — a "cross-tenant rejection"
 * error message against the receiving hospital's organization — which
 * is a surface the sender cannot read.
 *
 * <p>Modelled after the same intent as {@code Hl7AckBuilder.AckCode}
 * but kept on the service side so domain code never has to depend on
 * MLLP framing types.
 */
public enum MllpInboundOutcome {
    ACCEPTED,
    REJECTED_NOT_FOUND,
    REJECTED_INVALID,
    /**
     * The message is well formed and names patients the receiving hospital
     * holds, but it asks for something only another hospital may do — today,
     * an {@code ADT^A40} whose master identity is owned (stamped) by another
     * hospital. Terminal: AR, because resending cannot change the answer.
     *
     * <p>Not the cross-tenant outcome that used to exist. It may be returned
     * only AFTER the caller has established that the receiving hospital holds
     * a registration for every patient the message names, so it answers a
     * question about patients the sender's hospital can already see, and
     * says nothing about any it cannot.
     */
    REJECTED_NOT_OWNER
}
