package com.example.hms.payload.dto.portal;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalHealthReminderDTO {

    private UUID id;
    private String type;
    private String typeLabel;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dueDate;

    private String status;
    private String notes;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate completedDate;

    private String completedBy;
    private boolean overdue;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
}
