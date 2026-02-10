package com.example.hms.repository.reference;

import com.example.hms.model.reference.ReferenceCatalogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReferenceCatalogEntryRepository extends JpaRepository<ReferenceCatalogEntry, UUID> {

    Optional<ReferenceCatalogEntry> findByCatalogIdAndCodeIgnoreCase(UUID catalogId, String code);

    long countByCatalogId(UUID catalogId);
}
