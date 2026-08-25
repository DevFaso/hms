package com.example.hms.repository;

import com.example.hms.enums.BedStatus;
import com.example.hms.model.Bed;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BedRepository extends JpaRepository<Bed, UUID> {

    List<Bed> findByWard_Id(UUID wardId);

    List<Bed> findByWard_IdAndActiveTrue(UUID wardId);

    List<Bed> findByWard_IdOrderByBedNumberAsc(UUID wardId);

    /** Tenant guard — single-row lookups go through id + the ward's hospital. */
    Optional<Bed> findByIdAndWard_Hospital_Id(UUID id, UUID hospitalId);

    List<Bed> findByWard_Hospital_IdAndStatusAndActiveTrueOrderByBedNumberAsc(UUID hospitalId, BedStatus status);

    /**
     * Every bed on the board for one hospital, ward already fetched (Tier 2
     * item 31).
     *
     * <p>The board renders whole wards at a time, so the ward is join-fetched
     * rather than left to a lazy proxy per bed — sixty beds would otherwise be
     * sixty extra queries. Inactive beds and inactive wards are excluded: a
     * decommissioned bed is not a placement option and would distort the
     * denominator of the occupancy rate.
     */
    @Query("SELECT b FROM Bed b JOIN FETCH b.ward w "
        + "WHERE w.hospital.id = :hospitalId AND b.active = true AND w.active = true")
    List<Bed> findBoardBeds(@Param("hospitalId") UUID hospitalId);

    boolean existsByWard_IdAndBedNumberIgnoreCase(UUID wardId, String bedNumber);

    boolean existsByWard_IdAndBedNumberIgnoreCaseAndIdNot(UUID wardId, String bedNumber, UUID id);

    @Query("SELECT b.status, COUNT(b) FROM Bed b WHERE b.ward.hospital.id = :hospitalId AND b.active = true GROUP BY b.status")
    List<Object[]> countByHospitalGroupByStatus(@Param("hospitalId") UUID hospitalId);

    @Query("SELECT b.ward.id, b.ward.name, b.ward.wardType, b.status, COUNT(b) " +
           "FROM Bed b WHERE b.ward.hospital.id = :hospitalId AND b.active = true " +
           "GROUP BY b.ward.id, b.ward.name, b.ward.wardType, b.status")
    List<Object[]> countByHospitalGroupByWardAndStatus(@Param("hospitalId") UUID hospitalId);

    long countByWard_Hospital_IdAndActiveTrue(UUID hospitalId);

    @Query("SELECT b.ward.id, b.status, COUNT(b) FROM Bed b WHERE b.ward.hospital.id = :hospitalId AND b.active = true GROUP BY b.ward.id, b.status")
    List<Object[]> countByHospitalGroupByWardIdAndStatus(@Param("hospitalId") UUID hospitalId);

    /** Unscoped variant for super-admin ward listings across hospitals. */
    @Query("SELECT b.ward.id, b.status, COUNT(b) FROM Bed b WHERE b.active = true GROUP BY b.ward.id, b.status")
    List<Object[]> countAllGroupByWardIdAndStatus();
}
