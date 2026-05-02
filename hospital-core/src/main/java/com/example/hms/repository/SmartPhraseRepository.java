package com.example.hms.repository;

import com.example.hms.enums.SmartPhraseScope;
import com.example.hms.model.SmartPhrase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SmartPhraseRepository extends JpaRepository<SmartPhrase, UUID> {

    /**
     * Pull every SmartPhrase visible to a given user — global library, plus
     * the user's hospital scope, plus their personal collection. The service
     * applies the USER > HOSPITAL > GLOBAL precedence after this query.
     */
    @Query("""
        SELECT sp FROM SmartPhrase sp
         WHERE sp.scope = com.example.hms.enums.SmartPhraseScope.GLOBAL
            OR (sp.scope = com.example.hms.enums.SmartPhraseScope.HOSPITAL
                AND sp.hospital.id = :hospitalId)
            OR (sp.scope = com.example.hms.enums.SmartPhraseScope.USER
                AND sp.owner.id = :userId)
        """)
    List<SmartPhrase> findVisibleTo(@Param("userId") UUID userId,
                                    @Param("hospitalId") UUID hospitalId);

    /**
     * Trigger-prefix lookup for autocomplete. Works against the same visibility
     * rules as {@link #findVisibleTo} — the service narrows down further.
     */
    @Query("""
        SELECT sp FROM SmartPhrase sp
         WHERE LOWER(sp.trigger) LIKE LOWER(CONCAT(:prefix, '%'))
           AND (sp.scope = com.example.hms.enums.SmartPhraseScope.GLOBAL
             OR (sp.scope = com.example.hms.enums.SmartPhraseScope.HOSPITAL
                 AND sp.hospital.id = :hospitalId)
             OR (sp.scope = com.example.hms.enums.SmartPhraseScope.USER
                 AND sp.owner.id = :userId))
         ORDER BY sp.scope DESC, sp.trigger ASC
        """)
    List<SmartPhrase> searchByTriggerPrefix(@Param("prefix") String prefix,
                                            @Param("userId") UUID userId,
                                            @Param("hospitalId") UUID hospitalId);

    /** Existence check used by the upsert / unique-trigger guard. */
    Optional<SmartPhrase> findFirstByTriggerIgnoreCaseAndScopeAndHospital_IdAndOwner_Id(
        String trigger, SmartPhraseScope scope, UUID hospitalId, UUID ownerId);

    Optional<SmartPhrase> findFirstByTriggerIgnoreCaseAndScopeAndHospital_IdAndOwnerIsNull(
        String trigger, SmartPhraseScope scope, UUID hospitalId);

    Optional<SmartPhrase> findFirstByTriggerIgnoreCaseAndScopeAndHospitalIsNullAndOwnerIsNull(
        String trigger, SmartPhraseScope scope);

    Page<SmartPhrase> findByScope(SmartPhraseScope scope, Pageable pageable);

    @Modifying
    @Query("""
        UPDATE SmartPhrase sp
           SET sp.usageCount = sp.usageCount + 1,
               sp.lastUsedAt = :ts
         WHERE sp.id = :id
        """)
    int incrementUsage(@Param("id") UUID id, @Param("ts") LocalDateTime ts);
}
