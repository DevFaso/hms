package com.example.hms.repository;

import com.example.hms.model.NurseHandoffChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NurseHandoffChecklistItemRepository extends JpaRepository<NurseHandoffChecklistItem, UUID> {

    List<NurseHandoffChecklistItem> findByHandoff_IdOrderBySortOrderAsc(UUID handoffId);
}
