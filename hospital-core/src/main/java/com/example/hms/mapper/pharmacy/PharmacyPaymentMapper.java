package com.example.hms.mapper.pharmacy;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.PharmacyPayment;
import com.example.hms.payload.dto.pharmacy.PharmacyPaymentRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyPaymentResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class PharmacyPaymentMapper {

    public PharmacyPaymentResponseDTO toResponseDTO(PharmacyPayment entity) {
        if (entity == null) {
            return null;
        }

        return PharmacyPaymentResponseDTO.builder()
            .id(entity.getId())
            .dispenseId(entity.getDispense() != null ? entity.getDispense().getId() : null)
            .patientId(entity.getPatient() != null ? entity.getPatient().getId() : null)
            .hospitalId(entity.getHospital() != null ? entity.getHospital().getId() : null)
            .paymentMethod(entity.getPaymentMethod() != null ? entity.getPaymentMethod().name() : null)
            .amount(entity.getAmount())
            .currency(entity.getCurrency())
            .referenceNumber(entity.getReferenceNumber())
            .receivedBy(entity.getReceivedByUser() != null ? entity.getReceivedByUser().getId() : null)
            .notes(entity.getNotes())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    public PharmacyPayment toEntity(PharmacyPaymentRequestDTO dto,
                                    Dispense dispense,
                                    Patient patient,
                                    Hospital hospital,
                                    User receivedByUser) {
        if (dto == null) {
            return null;
        }

        return PharmacyPayment.builder()
            .dispense(dispense)
            .patient(patient)
            .hospital(hospital)
            .paymentMethod(dto.getPaymentMethod())
            .amount(dto.getAmount())
            .currency(dto.getCurrency() != null ? dto.getCurrency() : "XOF")
            .referenceNumber(dto.getReferenceNumber())
            .receivedByUser(receivedByUser)
            .notes(dto.getNotes())
            .build();
    }
}
