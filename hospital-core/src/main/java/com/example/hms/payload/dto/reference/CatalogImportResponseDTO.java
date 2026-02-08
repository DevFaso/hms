package com.example.hms.payload.dto.reference;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogImportResponseDTO {

    private ReferenceCatalogResponseDTO catalog;
    private int processed;
    private int created;
    private int updated;
    private int skipped;
}
