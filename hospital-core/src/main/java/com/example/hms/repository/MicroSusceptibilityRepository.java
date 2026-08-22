package com.example.hms.repository;

import com.example.hms.model.MicroSusceptibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface MicroSusceptibilityRepository extends JpaRepository<MicroSusceptibility, UUID> {

    List<MicroSusceptibility> findByIsolate_IdInOrderByAntibioticNameAsc(Collection<UUID> isolateIds);

    boolean existsByIsolate_IdAndAntibioticNameIgnoreCase(UUID isolateId, String antibioticName);
}
