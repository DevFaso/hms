package com.example.hms.service.recordaccess;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

/**
 * Policy 2026-11-20: carries the {@link ProxyReadContext} of the current
 * request from the grant check to the read filter.
 *
 * <p>Held as a request attribute, so it ends with the request and can never
 * follow a pooled thread into another one. With no request (a job, a test
 * that opens none) there is no context, and the filter then admits untagged
 * rows only: absence is the strict state.
 */
@Component
public class ProxyReadScope {

    private static final String ATTRIBUTE = ProxyReadScope.class.getName() + ".context";

    /** Called by the grant check only, with the grant it has just verified. */
    public void enter(ProxyReadContext context) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null && context != null) {
            request.setAttribute(ATTRIBUTE, context, RequestAttributes.SCOPE_REQUEST);
        }
    }

    public Optional<ProxyReadContext> current() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        Object held = request == null ? null : request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return held instanceof ProxyReadContext context ? Optional.of(context) : Optional.empty();
    }
}
