package com.example.hms.bootstrap;

import com.example.hms.repository.ArticleRepository;
import com.example.hms.repository.TestimonialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;


@Profile("dev")
@Component
@RequiredArgsConstructor
@Slf4j
public class DevContentDataLoader implements ApplicationRunner {

    private final ArticleRepository articleRepository;
    private final TestimonialRepository testimonialRepository;

    @Override
    public void run(ApplicationArguments args) {
        seedArticles();
        seedTestimonials();
    }

    private void seedArticles() {
    // Marketing content deprecated; skip seeding.
    if (articleRepository.count() > 0) return;
    log.debug("Skipping article seeding (feature deprecated)");
    }

    private void seedTestimonials() {
    // Marketing content deprecated; skip seeding.
    if (testimonialRepository.count() > 0) return;
    log.debug("Skipping testimonial seeding (feature deprecated)");
    }
}
