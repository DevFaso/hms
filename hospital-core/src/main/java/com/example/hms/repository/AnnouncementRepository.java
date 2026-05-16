package com.example.hms.repository;

import com.example.hms.model.Announcement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {
	Page<Announcement> findByHospital_IdOrderByDateDesc(UUID hospitalId, Pageable pageable);

	long countByHospital_Id(UUID hospitalId);
}
