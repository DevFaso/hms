package com.example.hms.controller;

import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.security.ReadsOnly;
import com.example.hms.testsupport.ControllerMappingRegistry;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D3753: a client still sending a search's terms in the URL gets 410 Gone naming the POST that
 * replaced it, never « invalid identifier » ({@code GET /patients/search} routed to
 * {@code GET /patients/{id}}) nor a silently unfiltered list ({@code GET /patients?search=}).
 */
@DisplayName("D3753: the retired GET searches answer 410 naming their POST")
class RetiredUrlSearchControllerTest {

    private static final String SAMPLE_ID = "00000000-0000-0000-0000-000000000001";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        mvc = MockMvcBuilders.standaloneSetup(new RetiredUrlSearchController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @AfterEach
    void tearDown() {
        MessageUtil.setMessageSource(null);
    }

    private static List<String> paths(String method) throws NoSuchMethodException {
        GetMapping mapping = RetiredUrlSearchController.class
            .getMethod(method, jakarta.servlet.http.HttpServletRequest.class).getAnnotation(GetMapping.class);
        return List.of(mapping.path().length > 0 ? mapping.path() : mapping.value());
    }

    private static HandlerMethod route(String method, String path, String search) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path.replaceAll("\\{[^/}]*}", SAMPLE_ID));
        if (search != null) {
            request.setParameter("search", search);
        }
        HandlerExecutionChain chain = ControllerMappingRegistry.mapping().getHandler(request);
        return chain == null ? null : (HandlerMethod) chain.getHandler();
    }

    @Test
    @DisplayName("a list's ?search= reaches the 410, the plain list its own GET, and the term its read-only POST")
    void aListsSearchParameterIsRetired() throws Exception {
        for (String path : paths("listSearchMovedToBody")) {
            assertThat(route("GET", path, "Awa").getMethod().getName()).as(path).isEqualTo("listSearchMovedToBody");
            HandlerMethod plain = route("GET", path, null);
            assertThat(plain.getBeanType()).as(path + " without a term").isNotEqualTo(RetiredUrlSearchController.class);
            HandlerMethod post = route("POST", path + RetiredUrlSearchController.TERM_SEARCH, null);
            assertThat(post.hasMethodAnnotation(ReadsOnly.class)).as("POST " + path + "/term-search").isTrue();
        }
    }

    @Test
    @DisplayName("a search moved to a POST at the same path answers 410 on GET, not « invalid identifier »")
    void aSearchMovedToAPostIsRetired() throws Exception {
        for (String path : paths("searchMovedToBody")) {
            assertThat(route("GET", path, null).getMethod().getName()).as(path).isEqualTo("searchMovedToBody");
            HandlerMethod post = route("POST", path, null);
            assertThat(post.hasMethodAnnotation(ReadsOnly.class)).as("POST " + path).isTrue();
        }
    }

    @Test
    @DisplayName("the answer is a translated 410 naming the POST, without the API prefix")
    void theAnswerNamesThePost() throws Exception {
        mvc.perform(get("/api/patients").contextPath("/api").param("search", "Patient Test 02")
                .header("Accept-Language", "fr"))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.message", allOf(
                containsString("POST"), containsString("/patients/term-search"), containsString("corps"))));

        mvc.perform(get("/api/patients/search").contextPath("/api").param("name", "Awa")
                .header("Accept-Language", "en"))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.message", allOf(
                containsString("body of a POST to /patients/search"))));
    }
}
