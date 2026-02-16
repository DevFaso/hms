package com.example.hms.service;

import com.example.hms.payload.dto.ArticleResponseDTO;
import com.example.hms.payload.dto.TestimonialResponseDTO;
import java.util.List;

public interface ContentService {
    List<ArticleResponseDTO> listArticles();
    List<TestimonialResponseDTO> listTestimonials();
}
