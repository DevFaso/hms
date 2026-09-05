package com.example.hms.repository.pro;

import com.example.hms.model.pro.ProInstrumentText;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProInstrumentTextRepository extends JpaRepository<ProInstrumentText, UUID> {

    List<ProInstrumentText> findByInstrument_IdAndLanguage(UUID instrumentId, String language);

    /** Languages the instrument has ANY text in — what the language picker offers. */
    @Query("SELECT DISTINCT t.language FROM ProInstrumentText t WHERE t.instrument.id = :instrumentId ORDER BY t.language")
    List<String> findLanguages(@Param("instrumentId") UUID instrumentId);
}
