package com.example.hms.upload;

import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.testsupport.UploadSamples;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The interceptor in front of every handler: a file reaches a handler only
 * when the handler names what it accepts, the file is that by content, and the
 * caller is within their rate and allowance.
 */
@DisplayName("Every multipart request is checked before its handler")
class UploadAdmissionInterceptorTest {

    /** Counts the requests that reached a handler. */
    private static final AtomicInteger HANDLED = new AtomicInteger();

    /** Handlers for the standalone MockMvc below (under /files, the prefix of the real upload endpoints). */
    @RestController
    static class Endpoints {
        @PostMapping(value = "/files/m16-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        @UploadPolicy(UploadKind.PATIENT_PHOTO)
        String photo(@RequestParam("file") MultipartFile file) {
            HANDLED.incrementAndGet();
            return "stored";
        }

        @PostMapping(value = "/files/m16-document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        @UploadPolicy(UploadKind.PATIENT_DOCUMENT)
        String withField(@RequestPart("file") MultipartFile file, @RequestPart("documentType") String type) {
            HANDLED.incrementAndGet();
            return type;
        }

        /** A multipart handler added without a policy. */
        @PostMapping(value = "/files/m16-unguarded", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        String unguarded(@RequestParam("file") MultipartFile file) {
            HANDLED.incrementAndGet();
            return "stored";
        }

        @PostMapping("/files/m16-json")
        String json() {
            HANDLED.incrementAndGet();
            return "ok";
        }
    }

    private final UploadLimiter limiter = mock(UploadLimiter.class);
    private MockMvc mvc;
    private MessageSource previous;

    @BeforeEach
    void setUp() {
        previous = (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        HANDLED.set(0);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("uploadLimiter", limiter);
        mvc = MockMvcBuilders.standaloneSetup(new Endpoints())
            .addInterceptors(new UploadAdmissionInterceptor(beans.getBeanProvider(UploadLimiter.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @AfterEach
    void restore() {
        MessageUtil.setMessageSource(previous);
    }

    private static MockMultipartFile photo(String name, String declared, byte[] content) {
        return new MockMultipartFile("file", name, declared, content);
    }

    @Test
    @DisplayName("a real photo within the limits reaches the handler, and is counted once")
    void aGoodFilePasses() throws Exception {
        byte[] jpeg = UploadSamples.jpeg();

        mvc.perform(multipart("/files/m16-photo").file(photo("face.jpg", "image/jpeg", jpeg))).andExpect(status().isOk());

        assertThat(HANDLED).hasValue(1);
        verify(limiter).admit(1, jpeg.length);
    }

    @Test
    @DisplayName("an SVG sent as a photo is refused in French, before the handler and before any allowance is spent")
    void anSvgPhotoIsRefused() throws Exception {
        mvc.perform(multipart("/files/m16-photo").file(photo("face.png", "image/png", UploadSamples.svg()))
                .locale(Locale.FRENCH))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Le contenu du fichier ne correspond pas à son type (.png)."));

        mvc.perform(multipart("/files/m16-photo").file(photo("face.svg", "image/svg+xml", UploadSamples.svg())))
            .andExpect(status().isBadRequest());

        assertThat(HANDLED).hasValue(0);
        verify(limiter, never()).admit(anyInt(), anyLong());
    }

    @Test
    @DisplayName("a part bound to a text parameter is a form field, not a file: only the file is checked and counted")
    void aTextPartIsNotAFile() throws Exception {
        byte[] pdf = UploadSamples.pdf();

        mvc.perform(multipart("/files/m16-document")
                .file(new MockMultipartFile("file", "bilan.pdf", "application/pdf", pdf))
                .file(new MockMultipartFile("documentType", "", "text/plain", "LAB_RESULT".getBytes())))
            .andExpect(status().isOk());

        assertThat(HANDLED).hasValue(1);
        verify(limiter).admit(1, pdf.length);
    }

    @Test
    @DisplayName("a multipart handler that names no policy receives no file")
    void anUnguardedHandlerIsRefused() throws Exception {
        mvc.perform(multipart("/files/m16-unguarded").file(photo("face.jpg", "image/jpeg", UploadSamples.jpeg()))
                .locale(Locale.FRENCH))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Les fichiers ne sont pas acceptés ici."));

        assertThat(HANDLED).hasValue(0);
    }

    @Test
    @DisplayName("a spent daily allowance answers 429 in the caller's language")
    void aSpentAllowanceIs429() throws Exception {
        // Built inside the request, as the limiter does: the message is in the caller's language.
        doAnswer(call -> {
            throw new UploadLimitExceededException("file.upload.quota.userFiles", 0, 300L);
        }).when(limiter).admit(anyInt(), anyLong());

        mvc.perform(multipart("/files/m16-photo").file(photo("face.jpg", "image/jpeg", UploadSamples.jpeg()))
                .locale(Locale.FRENCH))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().doesNotExist("Retry-After"))
            .andExpect(jsonPath("$.message").value(
                "Vous avez atteint votre limite de 300 fichiers sur 24 heures. Réessayez plus tard."));

        assertThat(HANDLED).hasValue(0);
    }

    @Test
    @DisplayName("a burst over the rate answers 429 with Retry-After")
    void aBurstIs429WithRetryAfter() throws Exception {
        doAnswer(call -> {
            throw new UploadLimitExceededException("file.upload.rateLimited", 60);
        }).when(limiter).admit(anyInt(), anyLong());

        mvc.perform(multipart("/files/m16-photo").file(photo("face.jpg", "image/jpeg", UploadSamples.jpeg()))
                .locale(Locale.ENGLISH))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "60"))
            .andExpect(jsonPath("$.message").value(
                "Too many files sent in a short time. Wait a minute and try again."));
    }

    @Test
    @DisplayName("a request that is not multipart is left alone")
    void otherRequestsAreUntouched() throws Exception {
        mvc.perform(post("/files/m16-json")).andExpect(status().isOk());

        assertThat(HANDLED).hasValue(1);
        verify(limiter, never()).admit(anyInt(), anyLong());
    }
}
