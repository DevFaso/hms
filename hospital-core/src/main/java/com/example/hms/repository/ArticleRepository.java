package com.example.hms.repository;

import com.example.hms.model.Article;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ArticleRepository extends JpaRepository<Article, UUID> {}
