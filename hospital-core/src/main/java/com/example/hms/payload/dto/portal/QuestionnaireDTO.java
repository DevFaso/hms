package com.example.hms.payload.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuestionnaireDTO {

    private UUID id;
    private String title;
    private String description;
    /** JSON array of question definitions. */
    private String questions;
    private Integer version;
    private UUID departmentId;
    private String departmentName;
}
