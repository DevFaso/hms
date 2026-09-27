package com.example.hms.service.integration.message;

import com.example.hms.model.Hospital;
import com.example.hms.utility.Hl7SenderText;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * The one place the MLLP paths derive what an
 * {@code integration_message_event} row is filed under.
 *
 * <p>A copy that drifts is a silent failure: rows for one sender split across
 * two {@code integration_id} values and the operator reading the DLQ sees half
 * the story, or an over-long id fails the insert and the row disappears
 * entirely. On the ADT and A40 paths that row is the only surviving record of
 * why a message was refused, so a silent drop lands exactly on the
 * misconfigured sender it exists to diagnose.
 *
 * <p>The dispatcher and the ADT and A40 services use this.
 * {@code MllpInboundLabServiceImpl} still has its own pair, and its
 * {@code buildIntegrationId} is the copy with no truncation — migrating it is
 * a two-line change this PR leaves to the lab stream, which has that file
 * open.
 */
public final class MllpRecordingContext {

    private static final Logger log = LoggerFactory.getLogger(MllpRecordingContext.class);

    /** The width of {@code integration_message_event.integration_id}. */
    private static final int INTEGRATION_ID_MAX = 120;

    private MllpRecordingContext() {}

    /**
     * {@code MLLP:<MSH-3>/<MSH-4>}, normalised and truncated to fit the
     * column.
     *
     * <p><b>Upper-cased, because the allowlist is.</b>
     * {@code MllpAllowedSenderServiceImpl.lookup} matches on
     * {@code trim().toUpperCase(ROOT)} against values V62 stores in canonical
     * upper case, so one allowlisted sender can present its own MSH-3/MSH-4
     * in any casing it likes and still resolve. Trimming alone would then let
     * that sender mint unlimited distinct integration ids — and therefore
     * unlimited distinct "stable" correlation ids — by varying its own case,
     * which both splits its DLQ rows and defeats the dedupe that keeps a
     * retry storm to one counted entry. Normalising here the same way the
     * allowlist does is what makes "one sender, one id" true rather than
     * aspirational.
     *
     * <p>The truncation is not cosmetic: the column is
     * {@code VARCHAR(120) NOT NULL} and HL7 v2.5 permits 180 characters in
     * each of MSH-3 and MSH-4, so a sender with a pathological header would
     * otherwise fail the insert — which {@link IntegrationMessageRecorder}
     * swallows by design.
     */
    public static String integrationId(String sendingApplication, String sendingFacility) {
        String raw = "MLLP:" + normalised(sendingApplication)
            + "/" + normalised(sendingFacility);
        return raw.length() > INTEGRATION_ID_MAX ? raw.substring(0, INTEGRATION_ID_MAX) : raw;
    }

    /**
     * The receiving hospital's organization, or null when it cannot be read.
     *
     * <p>{@code Hospital.organization} is LAZY, {@code Organization} takes its
     * id from a field-access {@code @Id}, and the hospital reaches an MLLP
     * worker thread detached from a read-only transaction that has already
     * closed — so reading this would throw, were it not for
     * {@code MllpAllowedSenderServiceImpl.resolveHospital} initialising the
     * organization before handing the hospital out. That is where the problem
     * is solved; this method just reads the result.
     *
     * <p>The catch is therefore not the normal path and must not become one.
     * It exists because the alternative is worse: on the ADT and A40 paths
     * this is called while building the row that is the only record of why a
     * message was refused, and losing that row to an initialisation bug
     * upstream would take the reason with it. A null organization is a
     * degraded row; no row is no evidence. The WARN is how you find out the
     * initialisation has regressed.
     */
    public static UUID organizationId(Hospital hospital) {
        if (hospital == null) {
            return null;
        }
        try {
            return hospital.getOrganization() == null ? null : hospital.getOrganization().getId();
        } catch (RuntimeException ex) {
            // Never silently: a null organization on a DLQ row otherwise reads
            // the same whether the hospital has none or the proxy could not be
            // read, and the row is the only record of the rejection. The
            // hospital is NOT dereferenced for the message — Hospital.getId()
            // off a detached association is the same trap, and throwing out of
            // this catch would defeat the point of having it.
            log.warn("MLLP recorder could not read the receiving hospital's organization; "
                + "the integration message row will be filed without one", ex);
            return null;
        }
    }

    /**
     * A correlation id that is the same for every occurrence of the same
     * problem, and different for different problems.
     *
     * <p>{@code countUnresolvedDeadLetters} counts a {@code FAILED} row only
     * when no later row shares its {@code correlationId}, so this is what
     * decides whether a retried refusal supersedes its own previous row or
     * stacks a new dead letter on the operator's badge. The ACK for a refused
     * MLLP message is AE, which senders treat as transient and retry on a
     * timer, so without this one misconfigured feed would post thousands of
     * unresolved dead letters a day and bury the refusals nobody has seen.
     *
     * <p><b>Never key this on something shared between senders.</b> The
     * newest row wins the count, so a scope that several senders fall into is
     * a denial-of-visibility primitive: anyone able to reach the port could
     * silence a real partner's dead letter by sending junk after it. A global
     * "unresolved sender" bucket was written and reverted for exactly that.
     *
     * <p>A sender that is not on the allowlist still gets its own scope, from
     * the MSH-3/MSH-4 it claims. Untrusted is not the same as unusable: the
     * worst an attacker does by varying what it claims is create additional
     * entries, which is noisy and loses nothing, whereas sharing a scope
     * destroys somebody else's. And because
     * {@link #integrationId(String, String)} normalises the pair the way the
     * allowlist matches it, a genuine partner that has been de-allowlisted
     * still collapses to one entry however it cases its headers.
     *
     * <p>A null or blank scope returns null, meaning "no dedupe": the
     * recorder mints a random id and every occurrence keeps its own counted
     * row and its own stored body.
     *
     * <p>Derived from the sender, the message type and the reason, and from
     * <b>nothing per-message</b>: no MSH-10, no identifier, no timestamp.
     * Anything per-message here would defeat the whole point, and an
     * identifier here would put PHI in an indexed column. Reasons are the
     * fixed strings the call sites pass, never text built from a message.
     *
     * <p>A name-based UUID rather than a readable key: it is deterministic,
     * always fits the {@code VARCHAR(120)} column, and is the same shape as
     * the random ids every other row carries, so nothing downstream has to
     * learn a second format.
     */
    public static String rejectionCorrelationId(String integrationId, String messageType,
                                                String reason) {
        // No scope, no id. Concatenating a null scope would produce the
        // literal "null" and therefore a perfectly stable, perfectly global
        // correlation id - the shared-scope defect this method's javadoc
        // warns about, arrived at by accident instead of on purpose. The
        // caller that passes null wants the recorder to mint a random one.
        if (!StringUtils.hasText(integrationId)) {
            return null;
        }
        String key = "mllp-reject|" + integrationId + "|" + messageType + "|" + reason;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * The correlation scope for a sender pair: normalised like the
     * {@link #integrationId} but <b>not truncated</b>.
     *
     * <p>Never stored, so it has no column to fit — and it must not borrow
     * the id's 120-character clamp. HL7 v2.5 permits 180 characters in each
     * of MSH-3 and MSH-4, so two senders agreeing on the first 120 would
     * share a correlation id by construction, which is the shared-scope
     * condition {@link #rejectionCorrelationId} calls a denial-of-visibility
     * primitive: one supersedes the other's counted row and overwrites its
     * stored body. The id is hashed into a UUID downstream, so length here
     * costs nothing.
     */
    public static String senderScope(String sendingApplication, String sendingFacility) {
        return "MLLP-SCOPE:" + normalised(sendingApplication)
            + "/" + normalised(sendingFacility);
    }

    /**
     * A dead-letter reason with MSH-10 appended - the one definition the ADT
     * and A40 paths share.
     *
     * <p>Whole, not capped: {@code Hl7MessageInspector} refuses an MSH-10
     * wider than the 255 of the columns it is stored in, and a shorter cap
     * would make two control ids that share a prefix indistinguishable in the
     * row that exists to tell them apart.
     *
     * <p>Quoted and escaped, because it is the sender's text inside a reason
     * an operator reads as ours. Unquoted, an MSH-10 of
     * {@code x) identifier not found; cross-tenant rejection (MSH-10 y}
     * renders as several findings the sender wrote. In quotes, with {@code "}
     * and backslash escaped, the value cannot end early, and any character
     * that could reorder or hide the text around it (control, bidi, zero-width,
     * separator, surrogate, private-use) is shown as a backslash-u hex escape
     * rather than rendered.
     */
    public static String withControlId(String reason, String messageControlId) {
        String quoted = quotedControlId(messageControlId);
        return quoted != null ? reason + " (MSH-10 " + quoted + ")" : reason;
    }

    /**
     * MSH-10 as it may be shown to an operator - in a dead-letter reason, an
     * audit description, a merge note or a log line - or null when there is
     * none. One rule for all of them: {@link Hl7SenderText#quote}, the rule for
     * every piece of sender text, after stripping surrounding spaces.
     *
     * <p>Only spaces are stripped from the ends, not {@code String.trim()}'s
     * whole control range: {@code ABC} and {@code ABC} followed by a BEL are
     * different ids, MSA-2 echoes them differently, and they must not render
     * the same here.
     */
    public static String quotedControlId(String messageControlId) {
        String key = messageControlIdKey(messageControlId);
        return key == null ? null : Hl7SenderText.quote(key);
    }

    /**
     * MSH-10 as an idempotency key and as the stored control id: the value as
     * sent, with only surrounding spaces stripped, or null when blank.
     *
     * <p><b>Not {@code String.trim()}.</b> {@code trim()} strips every
     * character up to U+0020, so {@code ABC} and {@code ABC} followed by a BEL
     * (or a tab, or an ESC) collapsed into one key: the second message read as
     * a replay of the first and was acknowledged without being stored. The key
     * is now the exact bounded value ({@code Hl7MessageInspector} holds MSH-10
     * to its 255-character column untrimmed), the same value MSA-2 echoes and
     * {@link #quotedControlId} shows.
     *
     * <p><b>Why spaces are still stripped.</b> Rows written before this rule
     * hold {@code trim()}med ids, and a replay must still match them. HL7 v2
     * string fields treat trailing blanks as insignificant, and senders that
     * pad fixed-width fields do send them, so a space-padded retry of a message
     * stored before the change must keep matching its row; stripping spaces
     * keeps exactly that. What no longer matches a legacy row is a retry whose
     * MSH-10 begins or ends with a control character: before the change it
     * was stored without it, now its key keeps it, so a pre-change message
     * retried after the deploy with such an id is stored a second time. That
     * needs a malformed id and a retry spanning the deploy; it is accepted as
     * the cost of no longer collapsing distinct ids.
     */
    public static String messageControlIdKey(String messageControlId) {
        if (!StringUtils.hasText(messageControlId)) {
            return null;
        }
        return stripSpaces(messageControlId);
    }

    /**
     * MSH-3 or MSH-4 as part of an idempotency key: trimmed and upper-cased
     * the way the allowlist matches it, or null when blank. One allowlisted
     * sender is one sender however it cases its header, so its replay key
     * must not split by case either - the same rule {@link #integrationId}
     * already applies to the row it files.
     */
    public static String senderKey(String senderField) {
        return StringUtils.hasText(senderField) ? senderField.trim().toUpperCase(Locale.ROOT) : null;
    }

    private static String stripSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * Exactly what {@code MllpAllowedSenderServiceImpl.lookup} does: trim and
     * upper-case. The allowlist matches that way against values V62 stores
     * canonically, so one sender may present its MSH-3/MSH-4 in any casing
     * and still resolve — and everything derived from the pair has to agree
     * with that, or one sender becomes several.
     */
    private static String normalised(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "?";
    }
}
