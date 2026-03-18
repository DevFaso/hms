package com.example.hms.repository;

import com.example.hms.model.Bed;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BedRepository extends JpaRepository<Bed, UUID> {

    List<Bed> findByWard_Id(UUID wardId);

    List<Bed> findByWard_IdAndActiveTrue(UUID wardId);

    @Query("SELECT b.status, COUNT(b) FROM Bed b WHERE b.ward.hospital.id = :hospitalId AND b.active = true GROUP BY b.status")
    List<Object[]> countByHospitalGroupByStatus(@Param("hospitalId") UUID hospitalId);

    @Query("SELECT b.ward.id, b.ward.name, b.ward.wardType, b.status, COUNT(b) " +
           "FROM Bed b WHERE b.ward.hospital.id = :hospitalId AND b.active = true " +
           "GROUP BY b.ward.id, b.ward.name, b.ward.wardType, b.status")
    List<Object[]> countByHospitalGroupByWardAndStatus(@Param("hospitalId") UUID hospitalId);

    long countByWard_Hospital_IdAndActiveTrue(UUID hospitalId);
}
