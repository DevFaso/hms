package com.example.hms.patient.repository;

import com.example.hms.patient.model.Patient;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientRepository extends JpaRepository<Patient, UUID> {
    boolean existsByMrnIgnoreCase(String mrn);

    @Query("""
        select p from Patient p
        where lower(p.mrn) like lower(concat('%', :query, '%'))
           or lower(p.firstName) like lower(concat('%', :query, '%'))
           or lower(p.lastName) like lower(concat('%', :query, '%'))
           or lower(p.phone) like lower(concat('%', :query, '%'))
        order by p.lastName, p.firstName
        """)
    List<Patient> search(@Param("query") String query);
}
