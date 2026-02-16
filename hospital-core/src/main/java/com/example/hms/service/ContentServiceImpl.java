package com.example.hms.service;

import com.example.hms.payload.dto.ArticleResponseDTO;
import com.example.hms.payload.dto.TestimonialResponseDTO;
import com.example.hms.repository.ArticleRepository;
import com.example.hms.repository.TestimonialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ContentServiceImpl implements ContentService {
    private final ArticleRepository articleRepository;
    private final TestimonialRepository testimonialRepository;

    @Override
    public List<ArticleResponseDTO> listArticles() {
        return articleRepository.findAll().stream()
                .sorted((a,b)-> b.getPublishedAt().compareTo(a.getPublishedAt()))
                .limit(12)
                .map(a -> ArticleResponseDTO.builder()
                        .id(a.getId())
                        .title(a.getTitle())
                        .excerpt(excerpt(a.getContent()))
                        .image(a.getImageUrl())
                        .author(a.getAuthor())
                        .publishedAt(a.getPublishedAt())
                        .build())
                .toList();
    }

    @Override
    public List<TestimonialResponseDTO> listTestimonials() {
        return testimonialRepository.findAll().stream()
                .sorted((a,b)-> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(20)
                .map(t -> TestimonialResponseDTO.builder()
                        .id(t.getId())
                        .author(t.getAuthor())
                        .role(t.getRoleLabel())
                        .text(t.getText())
                        .rating(t.getRating())
                        .avatar(t.getAvatarUrl())
                        .createdAt(t.getCreatedAt())
                        .build())
                .toList();
    }

    private String excerpt(String content) {
        if (content == null) return null;
        return content.length() > 140 ? content.substring(0,137) + "..." : content;
    }
}
