package com.example.hms.imaging.dicom;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * What a PACS URL may be. The DICOMweb base URL is typed by a hospital admin
 * and the backend then calls it, so an unchecked value would let them make
 * e-Keneya call into its own network (SSRF): cloud metadata, the database,
 * internal services.
 *
 * <ul>
 *   <li>{@code https} only, or {@code http} when private hosts are allowed (local dev);</li>
 *   <li>no user-info, query or fragment;</li>
 *   <li>the host must not resolve to a loopback, link-local, site-local,
 *       carrier-grade NAT, unique-local, multicast or wildcard address,
 *       unless {@code app.imaging.dicom-proxy.allow-private-hosts=true}.</li>
 * </ul>
 * Checked when the URL is saved and again before every call, since DNS can
 * change between the two. Redirects are never followed ({@link PacsDicomWebGateway}).
 */
public final class PacsUrlPolicy {

    /** Message keys (messages-common*.properties) a refusal carries. */
    public static final String INVALID = "imaging.pacs.url.invalid";
    public static final String PRIVATE_HOST = "imaging.pacs.url.privateHost";
    public static final String HTTPS_REQUIRED = "imaging.pacs.url.httpsOnly";

    private PacsUrlPolicy() {
    }

    /** A URL that breaks the policy. The message is a key, never the URL. */
    public static final class RejectedException extends RuntimeException {
        private final String messageKey;

        public RejectedException(String messageKey) {
            super(messageKey, null, false, false);
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    /** Trims and drops trailing slashes; null or blank stays null. */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String url = raw.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** Syntax and scheme only, no DNS: for a viewer link the browser, not the backend, opens. */
    public static URI requireWebUrl(String url, boolean allowHttp) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            throw new RejectedException(INVALID);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
            throw new RejectedException(INVALID);
        }
        if (!"https".equals(scheme) && !("http".equals(scheme) && allowHttp)) {
            throw new RejectedException("http".equals(scheme) ? HTTPS_REQUIRED : INVALID);
        }
        return uri;
    }

    /** The full check for a URL the backend will call. */
    public static void requireCallable(String url, boolean allowPrivateHosts) {
        URI uri = requireWebUrl(url, allowPrivateHosts);
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new RejectedException(INVALID);
        }
        if (allowPrivateHosts) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(IDN.toASCII(uri.getHost()));
        } catch (UnknownHostException | IllegalArgumentException ex) {
            throw new RejectedException(INVALID);
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw new RejectedException(PRIVATE_HOST);
            }
        }
    }

    static boolean isInternal(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            // 0.0.0.0/8, and 100.64.0.0/10 carrier-grade NAT (also private-network overlays)
            return first == 0 || (first == 100 && second >= 64 && second <= 127);
        }
        if (address instanceof Inet6Address) {
            // fc00::/7 unique-local
            return (b[0] & 0xfe) == 0xfc;
        }
        return false;
    }
}
