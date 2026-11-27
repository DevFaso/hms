package com.example.hms.config;

import com.example.hms.security.ClientAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Hands {@code app.audit.trusted-proxies} to {@link ClientAddress}: the
 * addresses (or leading parts of addresses, ending in '.' or ':') of an edge
 * that reaches the application from outside the private ranges, comma
 * separated. Empty by default: the private ranges are always our own.
 */
@Component
public class ClientAddressConfig {

    public ClientAddressConfig(@Value("${app.audit.trusted-proxies:}") String trustedProxies) {
        ClientAddress.trustAlso(Arrays.asList(trustedProxies.split(",")));
    }
}
