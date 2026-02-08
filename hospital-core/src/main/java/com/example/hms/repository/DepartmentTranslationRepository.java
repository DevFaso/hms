package com.example.hms.repository;

import com.example.hms.model.DepartmentTranslation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DepartmentTranslationRepository extends JpaRepository<DepartmentTranslation, UUID> {
}

