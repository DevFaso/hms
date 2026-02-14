package com.example.hms.bootstrap;

import com.example.hms.repository.ArticleRepository;
import com.example.hms.repository.TestimonialRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class DevContentDataLoaderTest {

    @Mock private ArticleRepository articleRepository;
    @Mock private TestimonialRepository testimonialRepository;
    @Mock private ApplicationArguments args;

    @InjectMocks private DevContentDataLoader loader;

    // ── run() delegates to both seed methods ─────────────────────

    @Test
    void runCallsSeedArticlesAndSeedTestimonials() {
        when(articleRepository.count()).thenReturn(0L);
        when(testimonialRepository.count()).thenReturn(0L);

        loader.run(args);

        verify(articleRepository).count();
        verify(testimonialRepository).count();
    }

    // ── seedArticles: count > 0 → return early ──────────────────

    @Test
    void seedArticlesSkipsWhenArticlesExist() {
        when(articleRepository.count()).thenReturn(5L);
        when(testimonialRepository.count()).thenReturn(0L);

        loader.run(args);

        verify(articleRepository).count();
        // only count() is called, no saveAll or other write operations
        verifyNoMoreInteractions(articleRepository);
    }

    // ── seedArticles: count == 0 → logs debug ───────────────────

    @Test
    void seedArticlesLogsDebugWhenNoArticlesExist() {
        when(articleRepository.count()).thenReturn(0L);
        when(testimonialRepository.count()).thenReturn(0L);

        loader.run(args);

        verify(articleRepository).count();
    }

    // ── seedTestimonials: count > 0 → return early ──────────────

    @Test
    void seedTestimonialsSkipsWhenTestimonialsExist() {
        when(articleRepository.count()).thenReturn(0L);
        when(testimonialRepository.count()).thenReturn(3L);

        loader.run(args);

        verify(testimonialRepository).count();
        verifyNoMoreInteractions(testimonialRepository);
    }

    // ── seedTestimonials: count == 0 → logs debug ───────────────

    @Test
    void seedTestimonialsLogsDebugWhenNoTestimonialsExist() {
        when(articleRepository.count()).thenReturn(0L);
        when(testimonialRepository.count()).thenReturn(0L);

        loader.run(args);

        verify(testimonialRepository).count();
    }

    // ── both have data → both skip ──────────────────────────────

    @Test
    void bothSeedMethodsSkipWhenDataExists() {
        when(articleRepository.count()).thenReturn(10L);
        when(testimonialRepository.count()).thenReturn(7L);

        loader.run(args);

        verify(articleRepository).count();
        verify(testimonialRepository).count();
        verifyNoMoreInteractions(articleRepository);
        verifyNoMoreInteractions(testimonialRepository);
    }

    // ── neither has data → both execute debug log path ──────────

    @Test
    void bothSeedMethodsExecuteWhenNoDataExists() {
        when(articleRepository.count()).thenReturn(0L);
        when(testimonialRepository.count()).thenReturn(0L);

        loader.run(args);

        verify(articleRepository).count();
        verify(testimonialRepository).count();
        // Both seed methods execute their debug log path
        verifyNoMoreInteractions(articleRepository);
    }
}
