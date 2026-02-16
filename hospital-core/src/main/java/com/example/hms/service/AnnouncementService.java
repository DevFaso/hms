package com.example.hms.service;

import com.example.hms.payload.dto.AnnouncementResponseDTO;
import java.util.List;
import java.util.UUID;

public interface AnnouncementService {
    List<AnnouncementResponseDTO> getAnnouncements(int limit);
    AnnouncementResponseDTO getAnnouncement(UUID id);
    AnnouncementResponseDTO createAnnouncement(String text);
    AnnouncementResponseDTO updateAnnouncement(UUID id, String text);
    void deleteAnnouncement(UUID id);
}
