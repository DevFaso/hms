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

	/**
	 * QA D4309: what the staff of one hospital read - that hospital's
	 * announcements and the platform's (no hospital), newest first. The list
	 * used to hold the hospital's only, so a platform announcement was shown to
	 * nobody but the super-admin who wrote it.
	 */
	@org.springframework.data.jpa.repository.Query(
		"SELECT a FROM Announcement a WHERE a.hospital.id = :hospitalId OR a.hospital IS NULL ORDER BY a.date DESC")
	java.util.List<Announcement> findVisibleAtHospital(
		@org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId, Pageable pageable);

	/** How many announcements {@link #findVisibleAtHospital} can show at this hospital. */
	@org.springframework.data.jpa.repository.Query(
		"SELECT COUNT(a) FROM Announcement a WHERE a.hospital.id = :hospitalId OR a.hospital IS NULL")
	long countVisibleAtHospital(@org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId);
}
