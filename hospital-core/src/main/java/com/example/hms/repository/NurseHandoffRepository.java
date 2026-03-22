package com.example.hms.repository;

import com.example.hms.enums.NurseHandoffStatus;
import com.example.hms.model.NurseHandoff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NurseHandoffRepository extends JpaRepository<NurseHandoff, UUID> {

    @Query("SELECT h FROM NurseHandoff h JOIN FETCH h.patient " +
           "WHERE h.hospital.id = :hospitalId AND h.status = :status " +
           "ORDER BY h.createdAt DESC")
    List<NurseHandoff> findByHospitalAndStatus(
        @Param("hospitalId") UUID hospitalId,
        @Param("status") NurseHandoffStatus status);

    @Query("SELECT h FROM NurseHandoff h JOIN FETCH h.patient " +
           "WHERE h.hospital.id = :hospitalId AND h.createdByStaff.id = :staffId " +
           "AND h.status = :status ORDER BY h.createdAt DESC")
    List<NurseHandoff> findByHospitalAndCreatorAndStatus(
        @Param("hospitalId") UUID hospitalId,
        @Param("staffId") UUID staffId,
        @Param("status") NurseHandoffStatus status);

    List<NurseHandoff> findByPatient_IdAndHospital_IdOrderByCreatedAtDesc(
        UUID patientId, UUID hospitalId);
}
