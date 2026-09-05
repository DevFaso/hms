package com.example.hms.repository.pro;

import com.example.hms.model.pro.ProInstrument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProInstrumentRepository extends JpaRepository<ProInstrument, UUID> {

    Optional<ProInstrument> findByCode(String code);

    Optional<ProInstrument> findByCodeAndActiveTrue(String code);

    List<ProInstrument> findByActiveTrueOrderByCodeAsc();
}
