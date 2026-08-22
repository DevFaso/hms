package com.example.hms.repository;

import com.example.hms.model.MicroIsolate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MicroIsolateRepository extends JpaRepository<MicroIsolate, UUID> {

    List<MicroIsolate> findByCultureResult_IdOrderByIsolateNumberAscCreatedAtAsc(UUID cultureResultId);

    long countByCultureResult_Id(UUID cultureResultId);
}
