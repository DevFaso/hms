package com.example.hms.bootstrap;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Says whether a configured host names this machine rather than a shared service: a loopback
 * address in any notation, the unspecified address, or the {@code localhost} name.
 *
 * <p>Never resolves a name: a DNS lookup at start-up can hang or answer differently from one
 * deploy to the next. IP literals are parsed here into bytes and handed to
 * {@link InetAddress#getByAddress(byte[])}, which does no lookup; any other host is a name, and
 * only {@code localhost} and its {@code *.localhost} sub-names (RFC 6761) count as local.
 *
 * <p>Recognised literal forms, all of which a resolver would accept:
 * <ul>
 *   <li>IPv4 in one to four parts: {@code 127.0.0.1}, {@code 127.1}, {@code 2130706433},
 *       {@code 0x7f.0.0.1}. A part with a leading zero or a {@code 0x} prefix reads differently
 *       to different parsers: Java and the Redis client read {@code 0127} as decimal 127,
 *       {@code inet_aton} as octal 87. Such a literal is read both ways and refused when either
 *       reading is local (fail closed);</li>
 *   <li>IPv6 in full, compressed or with an IPv4 tail, optionally in brackets and with a zone:
 *       {@code ::1}, {@code 0:0:0:0:0:0:0:1}, {@code [::1]}, {@code ::ffff:127.0.0.1}.</li>
 * </ul>
 * A host that looks like an IP literal but does not parse is treated as local (refused): it
 * cannot name the shared service either.
 */
final class LocalAddressCheck {

    /*
     * Each pattern below matches ONE label or group: the host is split first. A repeated group in
     * a java.util.regex pattern recurses once per repetition, so a long host could overflow the stack.
     */
    /** One numeric IPv4 label: decimal, octal or hex. */
    private static final Pattern NUMERIC_LABEL = Pattern.compile("0x[0-9a-f]*|[0-9]+");
    /** One IPv6 group: one to four hex digits. */
    private static final Pattern HEX_GROUP = Pattern.compile("[0-9a-f]{1,4}");
    private static final Pattern DECIMAL_OCTET = Pattern.compile("^[0-9]{1,3}$");
    /** What the parsers return for a literal that does not parse. */
    private static final byte[] INVALID = new byte[0];

    private LocalAddressCheck() {
    }

    /** Whether the host names this machine (or cannot name anything). Blank counts as local. */
    static boolean isLocal(String rawHost) {
        if (rawHost == null) {
            return true;
        }
        String host = rawHost.strip().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int zone = host.indexOf('%');
        if (zone >= 0) {
            host = host.substring(0, zone);
        }
        if (host.isEmpty()) {
            return true;
        }
        if (host.indexOf(':') >= 0) {
            return isLocalAddress(parseIpv6(host));
        }
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (name.isEmpty()) {
            return true;
        }
        if (allMatch(name.split("\\.", -1), NUMERIC_LABEL)) {
            // Fail closed on the ambiguous forms: local if either reading that parses is local,
            // or if neither parses.
            byte[] decimal = parseIpv4(name, false);
            byte[] inetAton = parseIpv4(name, true);
            if (decimal.length == 0 && inetAton.length == 0) {
                return true;
            }
            return (decimal.length != 0 && isLocalAddress(decimal))
                || (inetAton.length != 0 && isLocalAddress(inetAton));
        }
        return "localhost".equals(name) || name.endsWith(".localhost");
    }

    private static boolean isLocalAddress(byte[] bytes) {
        if (bytes.length != 4 && bytes.length != 16) {
            return true;
        }
        try {
            // getByAddress never resolves; a 16-byte IPv4-mapped address comes back as IPv4.
            InetAddress address = InetAddress.getByAddress(bytes);
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
                return true;
            }
        } catch (UnknownHostException e) {
            return true;
        }
        if (bytes.length == 16 && firstBytesZero(bytes, 12)) {
            // IPv4-compatible (::a.b.c.d): judge the embedded IPv4 address.
            return isLocalAddress(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
        }
        return false;
    }

    private static boolean allMatch(String[] parts, Pattern pattern) {
        for (String part : parts) {
            if (!pattern.matcher(part).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean firstBytesZero(byte[] bytes, int count) {
        for (int i = 0; i < count; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * One to four parts, the last filling the remaining bytes. With {@code inetAton}, a leading
     * zero means octal and {@code 0x} hex; without, every part is decimal (leading zeros ignored,
     * hex invalid), as Java reads it. Empty if invalid.
     */
    static byte[] parseIpv4(String host, boolean inetAton) {
        String[] parts = host.split("\\.", -1);
        if (parts.length > 4) {
            return INVALID;
        }
        long address = 0;
        for (int i = 0; i < parts.length; i++) {
            long value = parseIpv4Part(parts[i], inetAton);
            boolean last = i == parts.length - 1;
            long limit = last ? 1L << (8 * (5 - parts.length)) : 0x100;
            if (value < 0 || value >= limit) {
                return INVALID;
            }
            address |= last ? value : value << (8 * (3 - i));
        }
        return new byte[] {
            (byte) (address >>> 24), (byte) (address >>> 16), (byte) (address >>> 8), (byte) address
        };
    }

    /** A part in decimal, or with {@code inetAton} also octal (leading 0) or hex (0x); -1 if invalid. */
    private static long parseIpv4Part(String part, boolean inetAton) {
        try {
            if (!inetAton) {
                return part.isEmpty() || part.length() > 12 || part.startsWith("0x") ? -1 : Long.parseLong(part);
            }
            if (part.startsWith("0x")) {
                return part.length() == 2 || part.length() > 10 ? -1 : Long.parseLong(part.substring(2), 16);
            }
            if (part.isEmpty() || part.length() > 12) {
                return -1;
            }
            return part.length() > 1 && part.startsWith("0")
                ? Long.parseLong(part.substring(1), 8)
                : Long.parseLong(part);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** RFC 4291 text forms, with an optional dotted IPv4 tail. Empty if invalid. */
    static byte[] parseIpv6(String host) {
        String text = host;
        byte[] tail = INVALID;
        int lastColon = text.lastIndexOf(':');
        if (text.indexOf('.', lastColon) >= 0) {
            tail = strictIpv4(text.substring(lastColon + 1));
            if (tail.length != 4) {
                return INVALID;
            }
            // The IPv4 tail fills the last two groups: parse it as two placeholder groups.
            text = text.substring(0, lastColon + 1) + "0:0";
        }
        String[] halves = text.split("::", -1);
        if (halves.length > 2) {
            return INVALID;
        }
        for (String half : halves) {
            if (!half.isEmpty() && !allMatch(half.split(":", -1), HEX_GROUP)) {
                return INVALID;
            }
        }
        int[] head = groups(halves[0]);
        int[] rest = halves.length == 2 ? groups(halves[1]) : new int[0];
        int total = head.length + rest.length;
        if (halves.length == 1 ? total != 8 : total > 7) {
            return INVALID;
        }
        int[] all = new int[8];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(rest, 0, all, 8 - rest.length, rest.length);
        byte[] bytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            bytes[2 * i] = (byte) (all[i] >>> 8);
            bytes[2 * i + 1] = (byte) all[i];
        }
        if (tail.length == 4) {
            System.arraycopy(tail, 0, bytes, 12, 4);
        }
        return bytes;
    }

    /** Colon-separated hex groups, each already checked against {@link #HEX_GROUP}. */
    private static int[] groups(String text) {
        if (text.isEmpty()) {
            return new int[0];
        }
        String[] parts = text.split(":", -1);
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i], 16);
        }
        return values;
    }

    /** Dotted-quad only (the form an IPv6 tail must use). Empty if invalid. */
    private static byte[] strictIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return INVALID;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!DECIMAL_OCTET.matcher(parts[i]).matches()) {
                return INVALID;
            }
            int value = Integer.parseInt(parts[i]);
            if (value > 255) {
                return INVALID;
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }
}
