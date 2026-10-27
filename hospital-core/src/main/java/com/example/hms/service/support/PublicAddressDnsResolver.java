package com.example.hms.service.support;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * The DNS lookup an outbound HTTP client connects with, refusing any name
 * that answers with a non-public address ({@link OutboundUrlGuard#isPublic}).
 *
 * <p>Checking a URL when it is saved, or just before the call with a lookup of
 * its own, leaves DNS rebinding open: the name can answer with a public
 * address for the check and a private one for the client. Here the check runs
 * on the very answers the client connects to, so there is no second lookup to
 * rebind. TLS still verifies the certificate against the host name.
 */
public final class PublicAddressDnsResolver implements DnsResolver {

    private final DnsResolver upstream;

    public PublicAddressDnsResolver() {
        this(SystemDefaultDnsResolver.INSTANCE);
    }

    /** {@code upstream} answers the lookups; tests pass one that rebinds. */
    public PublicAddressDnsResolver(DnsResolver upstream) {
        this.upstream = upstream;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return OutboundUrlGuard.requireAllPublic(host, upstream.resolve(host));
    }

    /**
     * Never an unresolved address: the interface's default turns a null
     * answer into one, and the socket would then resolve the name itself,
     * unchecked.
     */
    @Override
    public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
        List<InetSocketAddress> addresses = new ArrayList<>();
        for (InetAddress address : resolve(host)) {
            addresses.add(new InetSocketAddress(address, port));
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return upstream.resolveCanonicalHostname(host);
    }
}
