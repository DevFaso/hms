package com.example.hms.service;

import com.example.hms.model.Announcement;
import com.example.hms.payload.dto.AnnouncementResponseDTO;
import com.example.hms.repository.AnnouncementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnnouncementServiceImpl implements AnnouncementService {
    private final AnnouncementRepository announcementRepository;

    @Override
    @Transactional(readOnly = true)
    public List<AnnouncementResponseDTO> getAnnouncements(int limit) {
        return announcementRepository.findAll().stream()
            .sorted((a, b) -> b.getDate().compareTo(a.getDate()))
            .limit(limit)
            .map(this::toDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AnnouncementResponseDTO getAnnouncement(UUID id) {
        Announcement announcement = announcementRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Announcement not found"));
        return toDTO(announcement);
    }

    @Override
    @Transactional
    public AnnouncementResponseDTO createAnnouncement(String text) {
        Announcement announcement = Announcement.builder()
            .text(text)
            .date(LocalDateTime.now())
            .build();
        return toDTO(announcementRepository.save(announcement));
    }

    @Override
    @Transactional
    public AnnouncementResponseDTO updateAnnouncement(UUID id, String text) {
        Announcement announcement = announcementRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Announcement not found"));
        announcement.setText(text);
        return toDTO(announcementRepository.save(announcement));
    }

    @Override
    @Transactional
    public void deleteAnnouncement(UUID id) {
        announcementRepository.deleteById(id);
    }

    private AnnouncementResponseDTO toDTO(Announcement announcement) {
        return new AnnouncementResponseDTO(
            announcement.getId(),
            announcement.getText(),
            announcement.getDate()
        );
    }
}
