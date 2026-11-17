package com.example.hms.controller.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

import com.example.hms.exception.BusinessException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How {@link StrictQueryParamsInterceptor} reads the accepted names off a handler (QA D801). */
@DisplayName("Strict query parameters")
class StrictQueryParamsInterceptorTest {

    private final StrictQueryParamsInterceptor interceptor = new StrictQueryParamsInterceptor();

    @SuppressWarnings("unused")
    static class Handlers {
        @StrictQueryParams
        public void search(@RequestParam(required = false) List<String> eventType,
                           @RequestParam(name = "q", required = false) String term,
                           Pageable pageable) {
            // Signature only: the interceptor reads the parameters, never runs the body.
        }

        @StrictQueryParams
        public void anything(@RequestParam Map<String, String> all) {
            // Signature only: the interceptor reads the parameters, never runs the body.
        }

        public void lax(@RequestParam(required = false) String eventType) {
            // Signature only: the interceptor reads the parameters, never runs the body.
        }
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        for (var method : Handlers.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return new HandlerMethod(new Handlers(), method);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private boolean call(String handler, String... params) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        for (int i = 0; i < params.length; i += 2) {
            request.addParameter(params[i], params[i + 1]);
        }
        return interceptor.preHandle(request, new MockHttpServletResponse(), handler(handler));
    }

    @Test
    void declaredNamesAndPagingAreAccepted() throws Exception {
        assertThat(call("search", "eventType", "LOGIN", "q", "x", "page", "0", "size", "5", "sort", "a,desc")).isTrue();
        assertThat(StrictQueryParamsInterceptor.accepted(handler("search")).names())
            .containsExactlyInAnyOrder("eventType", "q", "page", "size", "sort");
    }

    @Test
    void anUndeclaredNameIsRefusedAndNamed() {
        assertThatThrownBy(() -> call("search", "eventType", "LOGIN", "eventTypes", "LOGIN"))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException ex = (BusinessException) e;
                assertThat(ex.getMessageKey()).isEqualTo("error.request.unknownParameter");
                assertThat(ex.getArgs()[0]).isEqualTo("eventTypes");
            });
    }

    @Test
    void aVeryLongUnknownNameIsCutInTheMessage() {
        String name = "x".repeat(500);
        assertThatThrownBy(() -> call("search", name, "1"))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((String) ((BusinessException) e).getArgs()[0]).length()).isLessThan(70));
    }

    @Test
    void aWholeMapAcceptsAnything() throws Exception {
        assertThat(call("anything", "whatever", "1")).isTrue();
    }

    @Test
    void anUnmarkedHandlerIsNotChecked() throws Exception {
        assertThat(call("lax", "unknown", "1")).isTrue();
    }
}
