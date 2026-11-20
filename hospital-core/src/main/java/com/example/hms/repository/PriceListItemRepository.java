package com.example.hms.repository;

import com.example.hms.enums.PriceListLinkType;
import com.example.hms.model.PriceListItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing-money plan PR 2 (V302): a hospital's price-list items. Every read and write names the hospital: an item of
 * another hospital answers exactly as a missing one. The one unscoped read, {@link #findAllByOrderByCodeAsc}, serves
 * only the verified super-admin's global view (read-only).
 */
public interface PriceListItemRepository extends JpaRepository<PriceListItem, UUID> {

    Optional<PriceListItem> findByIdAndHospitalId(UUID id, UUID hospitalId);

    List<PriceListItem> findByHospitalIdOrderByCodeAsc(UUID hospitalId);

    /** Global view only (verified super-admin, no hospital named): every hospital's items, read-only. */
    List<PriceListItem> findAllByOrderByCodeAsc();

    boolean existsByHospitalIdAndCode(UUID hospitalId, String code);

    /**
     * Review of #1242: the items an invoice's lines were priced from, at the invoice's own hospital, read WITHOUT
     * the caller's tenant scope. {@code findAllById} is narrowed to the caller's hospitals, so a family proxy with
     * no assignment where the grantor was billed loaded nothing and the invoice with the hidden test was shown (the
     * QA D1212 trap). The hospital named here is the invoice's, never the caller's; only
     * {@code ProxyRecordFilter} calls it, to decide whether to withhold.
     */
    @Query("SELECT i FROM PriceListItem i WHERE i.hospitalId = :hospitalId AND i.id IN :ids")
    List<PriceListItem> findUnscopedByHospitalIdAndIdIn(@Param("hospitalId") UUID hospitalId,
                                                        @Param("ids") java.util.Collection<UUID> ids);

    /** The active item another one would collide with on the same link (one active item per link). */
    @Query("""
        SELECT i FROM PriceListItem i
        WHERE i.hospitalId = :hospitalId AND i.active = true
          AND i.linkType = :linkType AND i.linkKey = :linkKey
        """)
    List<PriceListItem> findActiveByLink(@Param("hospitalId") UUID hospitalId,
                                         @Param("linkType") PriceListLinkType linkType,
                                         @Param("linkKey") String linkKey);

    /**
     * Billing-money plan PR 3: the active item a charge is priced by, at its own hospital only, read under a share
     * lock (SELECT ... FOR SHARE) held until the checkout commits. A same-day correction of a price takes
     * {@link #lockByIdAtHospital} (FOR UPDATE) first, so it either waits for this checkout and then sees its lines,
     * or runs first and this checkout reads the corrected version: a line never copies a version replaced under it.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
        SELECT i FROM PriceListItem i
        WHERE i.hospitalId = :hospitalId AND i.active = true
          AND i.linkType = :linkType AND i.linkKey = :linkKey
        """)
    List<PriceListItem> findActiveByLinkForCharge(@Param("hospitalId") UUID hospitalId,
                                                  @Param("linkType") PriceListLinkType linkType,
                                                  @Param("linkKey") String linkKey);

    /**
     * Billing-money plan PR 4 review: every active linked item of the hospital, share-locked (SELECT ... FOR SHARE)
     * in code order, taken once at the start of a checkout. A checkout then holds its locks in the same order as the
     * CSV import takes its update locks ({@link #lockByHospitalIdAndCodes}), so the two never deadlock; the
     * per-charge {@link #findActiveByLinkForCharge} only re-reads rows already locked.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
        SELECT i FROM PriceListItem i
        WHERE i.hospitalId = :hospitalId AND i.active = true AND i.linkType IS NOT NULL
        ORDER BY i.code
        """)
    List<PriceListItem> lockActiveLinkedForCheckout(@Param("hospitalId") UUID hospitalId);

    /**
     * The item, locked (SELECT ... FOR UPDATE) and only at its own hospital. Every change to an item or its price
     * versions takes this lock first, so two concurrent "add price" calls are serialised: the second reads the
     * versions the first wrote.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM PriceListItem i WHERE i.id = :id AND i.hospitalId = :hospitalId")
    Optional<PriceListItem> lockByIdAtHospital(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId);

    /**
     * Billing-money plan PR 4: the items of these codes at this hospital, locked (SELECT ... FOR UPDATE) in code
     * order, as the CSV import applies a file: the same lock {@link #lockByIdAtHospital} takes, so a screen edit and
     * an import of the same item are serialised, and two imports take their locks in the same order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT i FROM PriceListItem i
        WHERE i.hospitalId = :hospitalId AND i.code IN :codes
        ORDER BY i.code
        """)
    List<PriceListItem> lockByHospitalIdAndCodes(@Param("hospitalId") UUID hospitalId,
                                                 @Param("codes") java.util.Collection<String> codes);
}
