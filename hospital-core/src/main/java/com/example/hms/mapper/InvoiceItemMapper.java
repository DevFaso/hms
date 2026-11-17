package com.example.hms.mapper;

import com.example.hms.model.BillingInvoice;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.Treatment;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.InvoiceItemRequestDTO;
import com.example.hms.payload.dto.InvoiceItemResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class InvoiceItemMapper {

    public InvoiceItem toInvoiceItem(InvoiceItemRequestDTO dto, BillingInvoice invoice) {
        return toInvoiceItem(dto, invoice, null, null);
    }

    public InvoiceItem toInvoiceItem(InvoiceItemRequestDTO dto,
                                     BillingInvoice invoice,
                                     UserRoleHospitalAssignment assignment,
                                     Treatment relatedService) {
        if (dto == null) return null;
        InvoiceItem item = new InvoiceItem();
        item.setBillingInvoice(invoice);
        item.setItemDescription(dto.getItemDescription());
        item.setQuantity(dto.getQuantity());
        item.setUnitPrice(dto.getUnitPrice());
        item.setItemCategory(dto.getItemCategory());
        item.setAssignment(assignment);
        item.setRelatedService(relatedService);
        // Billing-money plan PR 3: a line typed at the till is priced by hand.
        item.setPriceSource(com.example.hms.enums.InvoiceLinePriceSource.MANUAL);
        return item;
    }

    public void updateEntityFromDto(InvoiceItem entity,
                                    InvoiceItemRequestDTO dto,
                                    BillingInvoice invoice,
                                    UserRoleHospitalAssignment assignment,
                                    Treatment relatedService) {
        if (entity == null || dto == null) return;
        entity.setBillingInvoice(invoice);
        entity.setItemDescription(dto.getItemDescription());
        entity.setQuantity(dto.getQuantity());
        entity.setUnitPrice(dto.getUnitPrice());
        entity.setItemCategory(dto.getItemCategory());
        entity.setAssignment(assignment);
        entity.setRelatedService(relatedService);
    }

    public InvoiceItemResponseDTO toInvoiceItemResponseDTO(InvoiceItem item) {
        if (item == null) return null;

        InvoiceItemResponseDTO dto = new InvoiceItemResponseDTO();
        dto.setId(item.getId());

        if (item.getBillingInvoice() != null) {
            dto.setBillingInvoiceId(item.getBillingInvoice().getId());
            dto.setInvoiceNumber(item.getBillingInvoice().getInvoiceNumber());
        }

        // QA D1901: a line the server worded, in the reader's language; a typed one as typed.
        dto.setItemDescription(com.example.hms.service.billing.BillingTextRenderer.render(item.getTextCode(), item.getTextArgs(),
            item.getItemDescription()));
        dto.setQuantity(item.getQuantity());
        dto.setUnitPrice(item.getUnitPrice());
        dto.setTotalPrice(item.getTotalPrice());
        dto.setItemCategory(String.valueOf(item.getItemCategory()));

        if (item.getAssignment() != null) {
            dto.setAssignmentId(item.getAssignment().getId());
            dto.setStaffDisplay(buildAssigneeDisplay(item.getAssignment()));
        }

        if (item.getRelatedService() != null) {
            dto.setRelatedServiceId(item.getRelatedService().getId());
            dto.setRelatedServiceName(item.getRelatedService().getName());
        }

        com.example.hms.enums.InvoiceLinePriceSource source = item.getPriceSource();
        dto.setPriceSource(source != null ? source.name() : null);
        dto.setTariffUnitPrice(tariffUnitPriceOf(item));
        dto.setOverrideReason(item.getOverrideReason());

        dto.setCreatedAt(item.getCreatedAt());
        dto.setUpdatedAt(item.getUpdatedAt());
        return dto;
    }

    /** The price list's price for the line: the stored tariff when overridden, the line's own price when TARIFF. */
    public static java.math.BigDecimal tariffUnitPriceOf(InvoiceItem item) {
        if (item.getTariffUnitPrice() != null) {
            return item.getTariffUnitPrice();
        }
        return item.getPriceSource() == com.example.hms.enums.InvoiceLinePriceSource.TARIFF ? item.getUnitPrice() : null;
    }

    private String buildAssigneeDisplay(UserRoleHospitalAssignment a) {
        if (a == null) return null;
        try {
            var u = a.getUser();
            if (u != null) {
                String first = u.getFirstName() == null ? "" : u.getFirstName().trim();
                String last  = u.getLastName()  == null ? "" : u.getLastName().trim();
                String full  = (first + " " + last).trim();
                if (!full.isEmpty()) return full;
                if (u.getUsername() != null) return u.getUsername();
            }
        } catch (RuntimeException ignored) {
            // Best-effort display name construction; ignore lookup issues.
        }
        return "Staff";
    }
}
