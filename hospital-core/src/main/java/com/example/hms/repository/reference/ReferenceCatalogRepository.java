package com.example.hms.repository.reference;

import com.example.hms.model.reference.ReferenceCatalog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReferenceCatalogRepository extends JpaRepository<ReferenceCatalog, UUID> {

    Optional<ReferenceCatalog> findByCodeIgnoreCase(String code);
}
