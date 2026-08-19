package com.example.hms.mapper.pharmacy;

import com.example.hms.model.pharmacy.PharmacySale;
import com.example.hms.model.pharmacy.SaleLine;
import com.example.hms.payload.dto.pharmacy.PharmacySaleResponseDTO;
import com.example.hms.payload.dto.pharmacy.SaleLineResponseDTO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P-07: PharmacySale mapper.
 *
 * <p>Hand-written rather than MapStruct because (a) the entity references
 * (catalog item, stock lot) need null-safe extraction of denormalised fields
 * for the response, and (b) line items are owned by the sale aggregate so
 * construction of the entity graph happens in {@code PharmacySaleServiceImpl}.
 */
@Component
public class PharmacySaleMapper {

    public PharmacySaleResponseDTO toResponseDTO(PharmacySale sale) {
        if (sale == null) {
            return null;
        }
        List<SaleLineResponseDTO> lineDTOs = sale.getLines() == null
                ? List.of()
                : sale.getLines().stream().map(this::toLineResponseDTO).toList();

        return PharmacySaleResponseDTO.builder()
                .id(sale.getId())
                .pharmacyId(sale.getPharmacy() != null ? sale.getPharmacy().getId() : null)
                .pharmacyName(sale.getPharmacy() != null ? sale.getPharmacy().getName() : null)
                .hospitalId(sale.getHospital() != null ? sale.getHospital().getId() : null)
                .patientId(sale.getPatient() != null ? sale.getPatient().getId() : null)
                .soldByUserId(sale.getSoldByUser() != null ? sale.getSoldByUser().getId() : null)
                .saleDate(sale.getSaleDate())
                .paymentMethod(sale.getPaymentMethod() != null ? sale.getPaymentMethod().name() : null)
                .totalAmount(sale.getTotalAmount())
                .currency(sale.getCurrency())
                .referenceNumber(sale.getReferenceNumber())
                .status(sale.getStatus() != null ? sale.getStatus().name() : null)
                .notes(sale.getNotes())
                .lines(lineDTOs)
                .createdAt(sale.getCreatedAt())
                .updatedAt(sale.getUpdatedAt())
                .build();
    }

    public SaleLineResponseDTO toLineResponseDTO(SaleLine line) {
        if (line == null) {
            return null;
        }
        return SaleLineResponseDTO.builder()
                .id(line.getId())
                .medicationCatalogItemId(line.getMedicationCatalogItem() != null
                        ? line.getMedicationCatalogItem().getId() : null)
                .medicationName(line.getMedicationCatalogItem() != null
                        ? line.getMedicationCatalogItem().getNameFr() : null)
                .stockLotId(line.getStockLot() != null ? line.getStockLot().getId() : null)
                .quantity(line.getQuantity())
                .unitPrice(line.getUnitPrice())
                .lineTotal(line.getLineTotal())
                .notes(line.getNotes())
                .build();
    }
}
