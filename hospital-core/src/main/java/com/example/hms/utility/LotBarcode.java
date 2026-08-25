package com.example.hms.utility;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Mints the scannable identifier printed on a pharmacy stock-lot label
 * (Tier 2 item 34).
 *
 * <p>One place, because the format is a contract between two things that
 * never see each other: the label printer that renders the QR, and the
 * dispense check that compares a scan to the stored value. Two independent
 * copies of "LOT-" would be a defect waiting for someone to change one.
 *
 * <p><b>Not derived from the lot number.</b> {@code lot_number} is the
 * manufacturer's, so it is neither unique across suppliers nor guaranteed
 * stable — two consignments can carry the same printed lot number, and the
 * barcode has to be unique or the scan cannot identify which shelf the pack
 * came off. Nor is it the row's UUID: a UUID prints as a QR fine but is
 * unreadable to a human squinting at a smudged label, and the pharmacist
 * needs a fallback they can key in when the scanner will not read.
 *
 * <p>Twelve hex characters from a CSPRNG gives 2^48 — collision-safe for
 * any realistic stock volume, and short enough to read aloud. The unique
 * index in V138 is the actual guarantee; this only has to make a collision
 * rare enough never to be seen.
 */
public final class LotBarcode {

    /** Prefix, matching the {@code "LAB-"} precedent on lab specimens. */
    public static final String PREFIX = "LOT-";

    private static final int RANDOM_BYTES = 6; // 6 bytes -> 12 hex chars
    private static final SecureRandom RANDOM = new SecureRandom();

    private LotBarcode() {
    }

    /** A fresh barcode value, e.g. {@code "LOT-4f2a91c07b3e"}. */
    public static String mint() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + HexFormat.of().formatHex(bytes);
    }

    /**
     * Whether a string looks like one of ours. Used to give the pharmacist a
     * clearer message when they scan the wristband into the product field —
     * an easy mistake with two scan boxes on one screen, and "that is a
     * patient wristband, not a pack label" beats "no match".
     */
    public static boolean looksLikeLotBarcode(String value) {
        return value != null && value.trim().toUpperCase(java.util.Locale.ROOT).startsWith(PREFIX);
    }
}
