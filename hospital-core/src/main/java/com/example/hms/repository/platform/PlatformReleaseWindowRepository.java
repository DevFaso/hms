package com.example.hms.repository.platform;

import com.example.hms.enums.platform.PlatformReleaseStatus;
import com.example.hms.model.platform.PlatformReleaseWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlatformReleaseWindowRepository extends JpaRepository<PlatformReleaseWindow, UUID> {

    List<PlatformReleaseWindow> findByStatusIn(List<PlatformReleaseStatus> statuses);

    long countByStatusIn(List<PlatformReleaseStatus> statuses);

    long countByStatus(PlatformReleaseStatus status);

    long countByEndsAtAfter(LocalDateTime instant);

    Optional<PlatformReleaseWindow> findFirstByOrderByUpdatedAtDesc();

    /** Windows not cancelled and not yet over: scheduled or in progress by the clock (D7). */
    long countByStatusNotAndEndsAtAfter(PlatformReleaseStatus status, LocalDateTime instant);

    /** Windows not cancelled and not yet started (D7). */
    long countByStatusNotAndStartsAtAfter(PlatformReleaseStatus status, LocalDateTime instant);

    boolean existsByNameAndEnvironment(String name, String environment);
}
