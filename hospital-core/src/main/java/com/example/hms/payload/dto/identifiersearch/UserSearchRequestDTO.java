package com.example.hms.payload.dto.identifiersearch;

/** The filters of {@code POST /users/search}. Every field is optional; paging stays in the query. */
public record UserSearchRequestDTO(String name, String role, String email) {
}
