package com.example.hms.controller;

import com.example.hms.payload.dto.AnnouncementResponseDTO;
import com.example.hms.service.AnnouncementService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/announcements")
@RequiredArgsConstructor
@Tag(name = "Announcements", description = "Manage hospital-wide announcements")
public class AnnouncementController {
    private final AnnouncementService announcementService;

    @Operation(summary = "List announcements", description = "Get a list of recent announcements.")
    @ApiResponse(responseCode = "200", description = "List of announcements returned")
    @GetMapping
    public ResponseEntity<List<AnnouncementResponseDTO>> getAnnouncements(@RequestParam(defaultValue = "5") int limit) {
        return ResponseEntity.ok(announcementService.getAnnouncements(limit));
    }

    @Operation(summary = "Get announcement by ID", description = "Retrieve a specific announcement.")
    @ApiResponse(responseCode = "200", description = "Announcement found")
    @ApiResponse(responseCode = "404", description = "Announcement not found")
    @GetMapping("/{id}")
    public ResponseEntity<AnnouncementResponseDTO> getAnnouncement(@PathVariable UUID id) {
        return ResponseEntity.ok(announcementService.getAnnouncement(id));
    }


    @Operation(summary = "Create announcement", description = "Create a new announcement.")
    @ApiResponse(responseCode = "200", description = "Announcement created")
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<AnnouncementResponseDTO> createAnnouncement(@RequestParam String text) {
        return ResponseEntity.ok(announcementService.createAnnouncement(text));
    }


    @Operation(summary = "Update announcement", description = "Update an existing announcement.")
    @ApiResponse(responseCode = "200", description = "Announcement updated")
    @ApiResponse(responseCode = "404", description = "Announcement not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<AnnouncementResponseDTO> updateAnnouncement(@PathVariable UUID id, @RequestParam String text) {
        return ResponseEntity.ok(announcementService.updateAnnouncement(id, text));
    }


    @Operation(summary = "Delete announcement", description = "Delete an announcement.")
    @ApiResponse(responseCode = "204", description = "Announcement deleted")
    @ApiResponse(responseCode = "404", description = "Announcement not found")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<Void> deleteAnnouncement(@PathVariable UUID id) {
        announcementService.deleteAnnouncement(id);
        return ResponseEntity.noContent().build();
    }

}
