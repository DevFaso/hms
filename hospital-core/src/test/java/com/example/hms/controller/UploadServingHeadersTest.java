package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.repository.ChatMessageRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.ChatMessageService;
import com.example.hms.service.PatientPhotoService;
import com.example.hms.service.imaging.ImagingStudyImageService;
import com.example.hms.storage.StoredObject;
import com.example.hms.testsupport.UploadSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a stored upload leaves with, on each path that serves one: the type
 * derived from its content, {@code nosniff}, and a {@code Content-Disposition}
 * (inline only where a viewer shows a raster image or a PDF).
 */
@DisplayName("Stored uploads are served with their derived type, nosniff and a disposition")
class UploadServingHeadersTest {

    private static final UsernamePasswordAuthenticationToken CALLER =
        new UsernamePasswordAuthenticationToken("m16", "unused");

    @Test
    @DisplayName("the patient photo: the image type of its bytes, shown inline, nosniff, never cached")
    void patientPhoto() throws Exception {
        PatientPhotoService photos = mock(PatientPhotoService.class);
        UUID patientId = UUID.randomUUID();
        when(photos.load(any(), any())).thenReturn(new PatientPhotoService.PhotoPayload(UploadSamples.png(), "image/png"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new PatientPhotoController(photos, mock(ControllerAuthUtils.class))).build();

        mvc.perform(get("/patients/{id}/photo", patientId).principal(CALLER))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Disposition", startsWith("inline;")))
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("a patient photo payload typed as markup leaves as opaque bytes to download, never inline")
    void patientPhotoTypedAsMarkupIsADownload() throws Exception {
        PatientPhotoService photos = mock(PatientPhotoService.class);
        when(photos.load(any(), any()))
            .thenReturn(new PatientPhotoService.PhotoPayload(UploadSamples.svg(), "image/svg+xml"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new PatientPhotoController(photos, mock(ControllerAuthUtils.class))).build();

        mvc.perform(get("/patients/{id}/photo", UUID.randomUUID()).principal(CALLER))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/octet-stream"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Disposition", startsWith("attachment;")));
    }

    @Test
    @DisplayName("an imaging key image: inline for the viewer, nosniff, with a server-built name")
    void imagingImage() throws Exception {
        ImagingStudyImageService images = mock(ImagingStudyImageService.class);
        UUID imageId = UUID.randomUUID();
        when(images.readContent(imageId)).thenReturn(new ImagingStudyImageService.ImageContent(
            new StoredObject(new ByteArrayInputStream(UploadSamples.pdf()), UploadSamples.pdf().length),
            "application/pdf", imageId));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ImagingStudyImageController(images)).build();

        mvc.perform(get("/imaging/images/{id}/content", imageId))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/pdf"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Disposition",
                "inline; filename=\"imaging-image-" + imageId + ".pdf\""))
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("a chat attachment: always a download, nosniff; a stored type that is markup leaves as opaque bytes")
    void chatAttachment() throws Exception {
        ChatMessageService chat = mock(ChatMessageService.class);
        UUID attachmentId = UUID.randomUUID();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(chat, mock(SimpMessagingTemplate.class),
            mock(ChatMessageRepository.class), mock(UserRepository.class))).build();

        when(chat.downloadAttachment(attachmentId, "m16")).thenReturn(new ChatMessageService.ChatAttachmentPayload(
            new ByteArrayResource(UploadSamples.jpeg()), "image/jpeg", "plaie.jpg"));
        mvc.perform(get("/chat/attachments/{id}/download", attachmentId).principal(CALLER))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/jpeg"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"plaie.jpg\""));

        when(chat.downloadAttachment(attachmentId, "m16")).thenReturn(new ChatMessageService.ChatAttachmentPayload(
            new ByteArrayResource(UploadSamples.html()), "text/html", "page.html"));
        mvc.perform(get("/chat/attachments/{id}/download", attachmentId).principal(CALLER))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/octet-stream"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Disposition", startsWith("attachment;")));
    }
}
