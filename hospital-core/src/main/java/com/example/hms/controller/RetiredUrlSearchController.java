package com.example.hms.controller;

import com.example.hms.utility.MessageUtil;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The GET forms of the searches whose terms now travel in the body of a POST (QA D3750, D3753).
 *
 * <p>A client still sending one used to get a misleading answer: {@code GET /patients/search?…}
 * was routed to {@code GET /patients/{id}} and refused with « invalid identifier », and
 * {@code GET /patients?search=…} silently answered the unfiltered list. Each now answers
 * 410 Gone, in the caller's language, naming the POST that replaced it. Nothing is read: the
 * query string is never bound (no parameter here names it).
 *
 * <p>Not the audit reads: {@code GET /audit-logs?search=} is already refused by its
 * {@code @StrictQueryParams} (400 naming the parameter), and {@code GET /super-admin/audit-search}
 * has no GET twin to be confused with (405).
 */
@Hidden
@RestController
public class RetiredUrlSearchController {

    /** The suffix of the POST that replaced a list's {@code ?search=} filter. */
    static final String TERM_SEARCH = "/term-search";

    /**
     * A list whose typed term moved to {@code POST <list>/term-search}. Only when the old
     * {@code search} parameter is present: the plain list GET is unchanged.
     */
    @GetMapping(path = {
        "/patients",
        "/appointments",
        "/prescriptions",
        "/notifications",
        "/me/patient/notifications",
        "/assignments",
        "/super-admin/organizations/hierarchy"
    }, params = "search")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> listSearchMovedToBody(HttpServletRequest request) {
        throw movedTo(path(request) + TERM_SEARCH);
    }

    /** A search or lookup that is now a POST at the same path, its identifiers in the body. */
    @GetMapping({
        "/patients/search",
        "/patients/lookup",
        "/patients/registration-match",
        "/reception/patients/duplicate-candidates",
        "/staff/search/by-email",
        "/staff/search/by-phone",
        "/users/search",
        "/chat/messages/by-sender-email",
        "/chat/messages/by-sender-username",
        "/chat/messages/by-recipient-email",
        "/chat/messages/by-recipient-username"
    })
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> searchMovedToBody(HttpServletRequest request) {
        throw movedTo(path(request));
    }

    private static ResponseStatusException movedTo(String postPath) {
        return new ResponseStatusException(HttpStatus.GONE,
            MessageUtil.resolveOrRaw("error.request.searchMovedToBody", postPath));
    }

    /** The request's path as the API maps it: without the context path or a trailing slash. */
    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        String path = context != null && !context.isEmpty() && uri.startsWith(context)
            ? uri.substring(context.length())
            : uri;
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
