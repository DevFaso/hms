package com.example.hms.controller;

import com.example.hms.payload.dto.ArticleResponseDTO;
import com.example.hms.payload.dto.TestimonialResponseDTO;
import com.example.hms.payload.dto.TreatmentResponseDTO;
import com.example.hms.service.ContentService;
import com.example.hms.service.TreatmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

/**
 * Public (unauthenticated) read-only content for landing page.
 */
@RestController
@RequestMapping
@RequiredArgsConstructor
public class PublicContentController {
    private final TreatmentService treatmentService;
    private final ContentService contentService;

    @GetMapping("/services")
    public ResponseEntity<List<TreatmentResponseDTO>> services(
            @RequestHeader(name="Accept-Language", required=false) String acceptLanguage) {
        // Parse the first language tag safely (Spring can't convert full header with q-values directly to Locale)
        Locale locale = null;
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            try {
                String first = acceptLanguage.split(",")[0].trim(); // e.g. en-US
                // Normalize underscore to hyphen
                first = first.replace('_','-');
                if (!first.isEmpty()) {
                    locale = Locale.forLanguageTag(first);
                    if (locale != null && (locale.getLanguage()==null || locale.getLanguage().isEmpty())) {
                        locale = null; // invalid tag yields empty language
                    }
                }
            } catch (Exception ignored) { /* fallback to null */ }
        }
        String lang = (locale != null ? locale.getLanguage() : null);
        return ResponseEntity.ok(treatmentService.getAllTreatments(locale, lang));
    }

    @GetMapping("/articles")
    public ResponseEntity<List<ArticleResponseDTO>> articles() {
        return ResponseEntity.ok(contentService.listArticles());
    }

    @GetMapping("/testimonials")
    public ResponseEntity<List<TestimonialResponseDTO>> testimonials() {
        return ResponseEntity.ok(contentService.listTestimonials());
    }
}
