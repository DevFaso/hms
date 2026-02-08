package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentMinimalDTO {
    private UUID id;
    private String name;
    private String email;
    private String phoneNumber;

    public DepartmentMinimalDTO(UUID id, String name) {
        this.id = id;
        this.name = name;
    }
}

