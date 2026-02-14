package com.example.hms.service;

import com.example.hms.model.Article;
import com.example.hms.model.Testimonial;
import com.example.hms.payload.dto.ArticleResponseDTO;
import com.example.hms.payload.dto.TestimonialResponseDTO;
import com.example.hms.repository.ArticleRepository;
import com.example.hms.repository.TestimonialRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertAll;

@ExtendWith(MockitoExtension.class)
class ContentServiceImplTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private TestimonialRepository testimonialRepository;

    @InjectMocks
    private ContentServiceImpl service;

    // ───────────── helpers ─────────────

    private Article buildArticle(UUID id, String title, String content, String author,
                                  String imageUrl, OffsetDateTime publishedAt) {
        return Article.builder()
            .id(id)
            .title(title)
            .content(content)
            .author(author)
            .imageUrl(imageUrl)
            .publishedAt(publishedAt)
            .build();
    }

    private Testimonial buildTestimonial(UUID id, String author, String roleLabel,
                                          String text, Integer rating, String avatarUrl,
                                          OffsetDateTime createdAt) {
        return Testimonial.builder()
            .id(id)
            .author(author)
            .roleLabel(roleLabel)
            .text(text)
            .rating(rating)
            .avatarUrl(avatarUrl)
            .createdAt(createdAt)
            .build();
    }

    // ═══════════════ listArticles ═══════════════

    @Nested
    @DisplayName("listArticles")
    class ListArticles {

        @Test
        @DisplayName("returns empty list when no articles exist")
        void emptyList() {
            when(articleRepository.findAll()).thenReturn(Collections.emptyList());

            List<ArticleResponseDTO> result = service.listArticles();

            assertTrue(result.isEmpty());
            verify(articleRepository).findAll();
        }

        @Test
        @DisplayName("maps article fields correctly")
        void mapsFieldsCorrectly() {
            UUID id = UUID.randomUUID();
            OffsetDateTime pub = OffsetDateTime.now().minusDays(1);
            Article article = buildArticle(id, "Title", "Short content", "Author", "/img.png", pub);
            when(articleRepository.findAll()).thenReturn(List.of(article));

            List<ArticleResponseDTO> result = service.listArticles();

            assertEquals(1, result.size());
            ArticleResponseDTO dto = result.get(0);
            assertAll(
                () -> assertEquals(id, dto.getId()),
                () -> assertEquals("Title", dto.getTitle()),
                () -> assertEquals("Short content", dto.getExcerpt()),
                () -> assertEquals("/img.png", dto.getImage()),
                () -> assertEquals("Author", dto.getAuthor()),
                () -> assertEquals(pub, dto.getPublishedAt())
            );
        }

        @Test
        @DisplayName("sorts articles by publishedAt descending")
        void sortsByPublishedAtDesc() {
            OffsetDateTime oldest = OffsetDateTime.now().minusDays(10);
            OffsetDateTime newest = OffsetDateTime.now();
            Article old = buildArticle(UUID.randomUUID(), "Old", "c", "a", null, oldest);
            Article recent = buildArticle(UUID.randomUUID(), "New", "c", "a", null, newest);
            when(articleRepository.findAll()).thenReturn(List.of(old, recent));

            List<ArticleResponseDTO> result = service.listArticles();

            assertEquals(2, result.size());
            assertEquals("New", result.get(0).getTitle());
            assertEquals("Old", result.get(1).getTitle());
        }

        @Test
        @DisplayName("limits to 12 articles")
        void limitsTo12() {
            List<Article> articles = new ArrayList<>();
            OffsetDateTime base = OffsetDateTime.now();
            for (int i = 0; i < 15; i++) {
                articles.add(buildArticle(UUID.randomUUID(), "Art-" + i, "c", "a", null,
                    base.minusHours(i)));
            }
            when(articleRepository.findAll()).thenReturn(articles);

            List<ArticleResponseDTO> result = service.listArticles();

            assertEquals(12, result.size());
        }

        @Test
        @DisplayName("excerpt truncates content > 140 chars to 137 + '...'")
        void excerptTruncates() {
            String longContent = "A".repeat(200);
            Article article = buildArticle(UUID.randomUUID(), "T", longContent, "a", null, OffsetDateTime.now());
            when(articleRepository.findAll()).thenReturn(List.of(article));

            List<ArticleResponseDTO> result = service.listArticles();

            String excerpt = result.get(0).getExcerpt();
            assertEquals(140, excerpt.length());
            assertTrue(excerpt.endsWith("..."));
            assertEquals("A".repeat(137) + "...", excerpt);
        }

        @Test
        @DisplayName("excerpt returns content as-is when <= 140 chars")
        void excerptShortContent() {
            String shortContent = "A".repeat(140);
            Article article = buildArticle(UUID.randomUUID(), "T", shortContent, "a", null, OffsetDateTime.now());
            when(articleRepository.findAll()).thenReturn(List.of(article));

            List<ArticleResponseDTO> result = service.listArticles();

            assertEquals(shortContent, result.get(0).getExcerpt());
        }

        @Test
        @DisplayName("excerpt returns content exactly 140 chars without truncation")
        void excerptExact140() {
            String exact = "B".repeat(140);
            Article article = buildArticle(UUID.randomUUID(), "T", exact, "a", null, OffsetDateTime.now());
            when(articleRepository.findAll()).thenReturn(List.of(article));

            List<ArticleResponseDTO> result = service.listArticles();

            assertEquals(exact, result.get(0).getExcerpt());
        }

        @Test
        @DisplayName("excerpt at 141 chars gets truncated")
        void excerptAt141() {
            String content141 = "C".repeat(141);
            Article article = buildArticle(UUID.randomUUID(), "T", content141, "a", null, OffsetDateTime.now());
            when(articleRepository.findAll()).thenReturn(List.of(article));

            List<ArticleResponseDTO> result = service.listArticles();

            String excerpt = result.get(0).getExcerpt();
            assertEquals(140, excerpt.length());
            assertTrue(excerpt.endsWith("..."));
        }

        @Test
        @DisplayName("excerpt returns null when content is null")
        void excerptNullContent() {
            Article article = buildArticle(UUID.randomUUID(), "T", null, "a", null, OffsetDateTime.now());
            when(articleRepository.findAll()).thenReturn(List.of(article));

            List<ArticleResponseDTO> result = service.listArticles();

            assertNull(result.get(0).getExcerpt());
        }

        @Test
        @DisplayName("excerpt with empty string returns empty")
        void excerptEmptyContent() {
            Article article = buildArticle(UUID.randomUUID(), "T", "", "a", null, OffsetDateTime.now());
            when(articleRepository.findAll()).thenReturn(List.of(article));

            List<ArticleResponseDTO> result = service.listArticles();

            assertEquals("", result.get(0).getExcerpt());
        }
    }

    // ═══════════════ listTestimonials ═══════════════

    @Nested
    @DisplayName("listTestimonials")
    class ListTestimonials {

        @Test
        @DisplayName("returns empty list when no testimonials exist")
        void emptyList() {
            when(testimonialRepository.findAll()).thenReturn(Collections.emptyList());

            List<TestimonialResponseDTO> result = service.listTestimonials();

            assertTrue(result.isEmpty());
            verify(testimonialRepository).findAll();
        }

        @Test
        @DisplayName("maps testimonial fields correctly")
        void mapsFieldsCorrectly() {
            UUID id = UUID.randomUUID();
            OffsetDateTime created = OffsetDateTime.now().minusDays(2);
            Testimonial t = buildTestimonial(id, "Alice", "Doctor", "Great service", 5, "/avatar.png", created);
            when(testimonialRepository.findAll()).thenReturn(List.of(t));

            List<TestimonialResponseDTO> result = service.listTestimonials();

            assertEquals(1, result.size());
            TestimonialResponseDTO dto = result.get(0);
            assertAll(
                () -> assertEquals(id, dto.getId()),
                () -> assertEquals("Alice", dto.getAuthor()),
                () -> assertEquals("Doctor", dto.getRole()),
                () -> assertEquals("Great service", dto.getText()),
                () -> assertEquals(5, dto.getRating()),
                () -> assertEquals("/avatar.png", dto.getAvatar()),
                () -> assertEquals(created, dto.getCreatedAt())
            );
        }

        @Test
        @DisplayName("sorts testimonials by createdAt descending")
        void sortsByCreatedAtDesc() {
            OffsetDateTime oldest = OffsetDateTime.now().minusDays(10);
            OffsetDateTime newest = OffsetDateTime.now();
            Testimonial old = buildTestimonial(UUID.randomUUID(), "Old", "R", "t", 3, null, oldest);
            Testimonial recent = buildTestimonial(UUID.randomUUID(), "New", "R", "t", 4, null, newest);
            when(testimonialRepository.findAll()).thenReturn(List.of(old, recent));

            List<TestimonialResponseDTO> result = service.listTestimonials();

            assertEquals(2, result.size());
            assertEquals("New", result.get(0).getAuthor());
            assertEquals("Old", result.get(1).getAuthor());
        }

        @Test
        @DisplayName("limits to 20 testimonials")
        void limitsTo20() {
            List<Testimonial> list = new ArrayList<>();
            OffsetDateTime base = OffsetDateTime.now();
            for (int i = 0; i < 25; i++) {
                list.add(buildTestimonial(UUID.randomUUID(), "A-" + i, "R", "t", 3, null,
                    base.minusHours(i)));
            }
            when(testimonialRepository.findAll()).thenReturn(list);

            List<TestimonialResponseDTO> result = service.listTestimonials();

            assertEquals(20, result.size());
        }
    }
}
