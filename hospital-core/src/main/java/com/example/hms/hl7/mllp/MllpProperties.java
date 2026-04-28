package com.example.hms.hl7.mllp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Configuration for the HL7 v2 MLLP TCP listener.
 *
 * <p>The listener is disabled by default. In a West-Africa deployment it is
 * typically enabled per hospital — analyzers (Mindray, Sysmex, Roche Cobas)
 * push ORU^R01 and ADT feeds over MLLP from the LIS network. Set
 * {@code APP_HL7_MLLP_ENABLED=true} on the production env to activate.
 *
 * <p>Defaults bind to the IANA-registered HL7 port 2575 on all interfaces;
 * production deployments should pin {@link #bindAddress} to a private
 * interface and front the port with a firewall / mTLS terminator.
 */
@ConfigurationProperties(prefix = "app.hl7.mllp")
public class MllpProperties {

    /** Master switch. When false the listener bean is not even created. */
    private boolean enabled = false;

    /**
     * TCP port to bind. Use {@code 0} to bind an ephemeral port (only useful
     * for tests; the chosen port is exposed via {@link MllpTcpServer#getBoundPort()}).
     */
    private int port = 2575;

    /** Local interface to bind to. {@code 0.0.0.0} = all. */
    private String bindAddress = "0.0.0.0";

    /** Wire-level charset. HL7 v2 traditionally uses ISO-8859-1; UTF-8 is acceptable when both ends agree. */
    private String charset = StandardCharsets.UTF_8.name();

    /** Maximum frame size in bytes. Defends against junk traffic that never sends FS+CR. */
    private int maxFrameBytes = 1_048_576;   // 1 MB

    /** Per-socket read timeout in ms. After this with no data the connection is closed. */
    private int readTimeoutMs = 60_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String bindAddress) { this.bindAddress = bindAddress; }

    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }

    public int getMaxFrameBytes() { return maxFrameBytes; }
    public void setMaxFrameBytes(int maxFrameBytes) { this.maxFrameBytes = maxFrameBytes; }

    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

    public Charset resolvedCharset() {
        return Charset.forName(charset);
    }
}
