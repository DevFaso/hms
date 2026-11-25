package com.example.hms.i18n;

import com.example.hms.controller.FileUploadController;
import com.example.hms.service.FileUploadService;
import com.example.hms.service.UserService;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ControllerResponseLocaleTest {
    private MessageSource previous;
    private LocaleContext previousLocale;
    private FileUploadService uploads;
    private UserService users;
    private MockMvc mvc;

    @BeforeEach void setup() {
        previous = (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");
        previousLocale = LocaleContextHolder.getLocaleContext();
        var messages = TestMessageSources.bundles();
        MessageUtil.setMessageSource(messages);
        uploads = mock(FileUploadService.class);
        users = mock(UserService.class);
        mvc = MockMvcBuilders.standaloneSetup(new FileUploadController(uploads, users, messages)).build();
    }

    @AfterEach void restore() {
        MessageUtil.setMessageSource(previous);
        LocaleContextHolder.setLocaleContext(previousLocale);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "fr|Utilisateur introuvable.",
        "en|User not found",
        "es|No se ha encontrado al usuario."
    })
    void missingUploadUserIsLocalizedWithoutCallingStorage(String language, String expected) throws Exception {
        mvc.perform(multipart("/files/profile-image").file(file())
                .principal(new UsernamePasswordAuthenticationToken("cdx", "unused"))
                .locale(Locale.forLanguageTag(language)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(expected));
        verifyNoInteractions(uploads);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "fr|Impossible d’envoyer la photo de profil.",
        "en|Failed to upload profile image",
        "es|No se ha podido subir la foto de perfil."
    })
    void storageFailureKeepsItsStatusAndDoesNotExposeTheException(String language, String expected) throws Exception {
        when(users.getUserIdByUsername("cdx")).thenReturn(UUID.randomUUID());
        when(uploads.uploadProfileImage(any(), any())).thenThrow(new IOException("cdx-private-path"));
        mvc.perform(multipart("/files/profile-image").file(file())
                .principal(new UsernamePasswordAuthenticationToken("cdx", "unused"))
                .locale(Locale.forLanguageTag(language)))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value(expected));
        verify(users, never()).updateProfileImage(any(), any());
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "cdx.png", "image/png", new byte[]{1});
    }
}
