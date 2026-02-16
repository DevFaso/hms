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

        dto.setItemDescription(item.getItemDescription());
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

        dto.setCreatedAt(item.getCreatedAt());
        dto.setUpdatedAt(item.getUpdatedAt());
        return dto;
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
