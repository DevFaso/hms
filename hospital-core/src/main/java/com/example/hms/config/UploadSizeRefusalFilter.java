package com.example.hms.config;

import com.example.hms.service.FileUploadService;
import com.example.hms.utility.MessageUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * QA D2465 (r64): an upload over the limit is refused in the caller's
 * language, whatever its size.
 *
 * <p>A file of 10–20 MB used to get a bare 413 « Content Too Large » with no
 * message: the servlet container parses the multipart body the first time a
 * filter reads a request parameter, which is before the dispatcher, so the
 * size failure never reached {@code GlobalExceptionHandler} and fell to the
 * container's error page. This filter runs first on every multipart request:
 * a body larger than the request limit is refused from its declared length,
 * and the parts are parsed here, where a size failure is answered with the
 * same localized 413 the handler gives ({@code error.upload.tooLarge}). Any
 * other parse failure is left for the dispatcher to answer, as before.
 *
 * <p>No collaborators: a filter with a service dependency breaks every
 * {@code @WebMvcTest} slice (#536).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadSizeRefusalFilter extends OncePerRequestFilter {

    private final long maxRequestBytes;

    public UploadSizeRefusalFilter(
            @Value("${spring.servlet.multipart.max-request-size:11MB}") String maxRequestSize) {
        this.maxRequestBytes = DataSize.parse(maxRequestSize).toBytes();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        return type == null || !type.toLowerCase(Locale.ROOT).startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxRequestBytes) {
            refuse(request, response);
            return;
        }
        try {
            request.getParts();
        } catch (IllegalStateException | ServletException | IOException ex) {
            if (isSizeFailure(ex)) {
                refuse(request, response);
                return;
            }
            // Any other failure is answered where it always was, by the dispatcher.
        }
        chain.doFilter(request, response);
    }

    /** The container's size refusals, whichever class it uses (Tomcat renamed them across versions). */
    static boolean isSizeFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String name = t.getClass().getSimpleName();
            if (name.contains("SizeLimit") || name.contains("SizeException") || name.contains("ByteCountLimit")
                    || name.contains("MaxUploadSizeExceeded")) {
                return true;
            }
        }
        return false;
    }

    private static void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Locale locale = request.getLocale();
        String label = FileUploadService.maxUploadLabel(locale);
        String message = MessageUtil.resolveOrNull(locale, "error.upload.tooLarge", new Object[] {label});
        if (message == null) {
            message = "File size exceeds the maximum allowed limit of " + label;
        }
        HttpStatus status = HttpStatus.PAYLOAD_TOO_LARGE;
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"timestamp\":\"" + LocalDateTime.now() + "\",\"status\":" + status.value()
            + ",\"error\":\"" + status.getReasonPhrase() + "\",\"message\":\"" + json(message)
            + "\",\"path\":\"" + json(request.getRequestURI()) + "\"}");
    }

    private static String json(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
