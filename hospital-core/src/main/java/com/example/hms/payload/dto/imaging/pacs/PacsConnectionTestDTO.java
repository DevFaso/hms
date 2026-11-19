package com.example.hms.payload.dto.imaging.pacs;

/**
 * The admin's « test the connection » answer.
 *
 * @param result OK, PROXY_DISABLED, NOT_CONFIGURED, UNREACHABLE, UNAUTHORIZED, REJECTED or URL_REFUSED
 * @param source HOSPITAL or PLATFORM: whose PACS was tried, null when none was
 */
public record PacsConnectionTestDTO(String result, String source) {
}
