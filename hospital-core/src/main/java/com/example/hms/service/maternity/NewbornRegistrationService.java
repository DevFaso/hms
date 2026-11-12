package com.example.hms.service.maternity;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.InfantSex;
import com.example.hms.enums.ProxyRelationship;
import com.example.hms.enums.ProxyStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProxy;
import com.example.hms.model.labor.DeliveryNewborn;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.clinical.labor.DeliveryNewbornDTO;
import com.example.hms.repository.DeliveryNewbornRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProxyRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.PatientAccountRegistrationService;
import com.example.hms.service.i18n.NotificationLocales;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * QA D1592: a recorded live birth registers each baby as its own patient.
 *
 * <p>One patient per live-born baby ({@code numberOfInfants}; twins are two),
 * at the delivering hospital, through the normal registration path
 * ({@link PatientAccountRegistrationService#registerNewborn}: account, record,
 * hospital registration and MRN). The baby's date of birth is the delivery's;
 * its sex is the recorded one for the first baby (the record carries the
 * primary infant's sex only), otherwise left unknown. The name is provisional
 * ("Bébé" + the mother's surname, numbered for a multiple birth) until the
 * desk edits it. The mother is linked as parent: the delivery link
 * ({@link DeliveryNewborn}), the baby's emergency contact, and a portal proxy
 * grant so the mother's portal shows the baby.
 *
 * <p>Idempotent: babies already registered for the delivery are never
 * registered again. A stillbirth registers no one. A count that falls below
 * the babies already registered (or a delivery no longer a live birth) never
 * deletes a patient: the extra rows are flagged for review.
 *
 * <p>Tenancy: the baby is registered at the delivering hospital only. The
 * mother's registrations or sharing elsewhere are not copied to the baby.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NewbornRegistrationService {

    static final String PROXY_PERMISSIONS = "ALL";
    static final String REVIEW_COUNT_LOWERED = "LIVE_BIRTH_COUNT_LOWERED";
    static final String REVIEW_NOT_LIVE_BIRTH = "NO_LONGER_LIVE_BIRTH";
    private static final String ENTITY_PATIENT = "PATIENT";

    private final DeliveryNewbornRepository deliveryNewbornRepository;
    private final PatientAccountRegistrationService patientAccountRegistrationService;
    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final PatientProxyRepository patientProxyRepository;
    private final MessageSource messageSource;
    private final AuditEventLogService auditEventLogService;

    /**
     * Register the live-born babies of a saved delivery that are not yet
     * registered, and flag any registered baby the record no longer counts.
     *
     * @return every baby registered for this delivery, in birth order
     */
    @Transactional
    public List<DeliveryNewbornDTO> registerLiveBorn(DeliveryRecord delivery, UUID recorderUserId) {
        List<DeliveryNewborn> existing = new ArrayList<>(
            deliveryNewbornRepository.findByDeliveryRecord_IdOrderByBirthOrderAsc(delivery.getId()));
        int expected = delivery.isLiveBirth() ? Math.max(delivery.getNumberOfInfants(), 0) : 0;

        for (DeliveryNewborn row : existing) {
            if (row.getBirthOrder() > expected && !row.isNeedsReview()) {
                row.setNeedsReview(true);
                row.setReviewReason(delivery.isLiveBirth() ? REVIEW_COUNT_LOWERED : REVIEW_NOT_LIVE_BIRTH);
                deliveryNewbornRepository.save(row);
                log.warn("Delivery {} no longer counts newborn {}: flagged for review", delivery.getId(), row.getId());
            }
        }

        java.util.Set<Integer> registeredOrders = new java.util.HashSet<>();
        existing.forEach(row -> registeredOrders.add(row.getBirthOrder()));
        for (int order = 1; order <= expected; order++) {
            if (!registeredOrders.contains(order)) {
                existing.add(registerOne(delivery, order, expected, recorderUserId));
            }
        }
        existing.sort(java.util.Comparator.comparingInt(DeliveryNewborn::getBirthOrder));
        return existing.stream().map(this::toDto).toList();
    }

    /** The babies a delivery registered, in birth order (read side of the delivery record). */
    @Transactional(readOnly = true)
    public List<DeliveryNewbornDTO> newbornsOf(DeliveryRecord delivery) {
        if (delivery == null || delivery.getId() == null) {
            return List.of();
        }
        return deliveryNewbornRepository.findByDeliveryRecord_IdOrderByBirthOrderAsc(delivery.getId()).stream()
            .map(this::toDto)
            .toList();
    }

    private DeliveryNewborn registerOne(DeliveryRecord delivery, int order, int count, UUID recorderUserId) {
        Patient mother = delivery.getPatient();
        UUID hospitalId = delivery.getHospital().getId();
        Locale locale = NotificationLocales.STAFF;
        String firstName = count > 1
            ? messageSource.getMessage("newborn.registration.provisionalFirstNameOrdered", new Object[]{order}, locale)
            : messageSource.getMessage("newborn.registration.provisionalFirstName", null, locale);
        String lastName = mother.getLastName();

        AdminSignupRequest account = new AdminSignupRequest();
        account.setFirstName(firstName);
        account.setLastName(lastName);
        account.setPatientRegistration(true);

        PatientRequestDTO record = new PatientRequestDTO();
        record.setFirstName(firstName);
        record.setLastName(lastName);
        record.setDateOfBirth(delivery.getBirthDateTime().toLocalDate());
        record.setGender(order == 1 ? genderOf(delivery.getInfantSex()) : null);
        record.setPhoneNumberPrimary(mother.getPhoneNumberPrimary());
        record.setAddress(mother.getAddress());
        record.setAddressLine1(mother.getAddressLine1());
        record.setAddressLine2(mother.getAddressLine2());
        record.setCity(mother.getCity());
        record.setState(mother.getState());
        record.setZipCode(mother.getZipCode());
        record.setCountry(mother.getCountry());
        record.setEmergencyContactName(mother.getFullName());
        record.setEmergencyContactPhone(mother.getPhoneNumberPrimary());
        record.setEmergencyContactRelationship(
            messageSource.getMessage("newborn.registration.motherRelationship", null, locale));
        record.setActive(true);

        PatientResponseDTO created = patientAccountRegistrationService.registerNewborn(account, record, hospitalId, locale);
        Patient baby = patientRepository.getReferenceById(created.getId());

        DeliveryNewborn link = deliveryNewbornRepository.save(DeliveryNewborn.builder()
            .deliveryRecord(delivery)
            .newbornPatient(baby)
            .motherPatient(mother)
            .hospital(delivery.getHospital())
            .birthOrder(order)
            .build());
        grantMotherProxy(baby, mother, locale);
        auditAfterCommit(created.getId(), hospitalId, delivery.getHospital().getName(), delivery.getId(), order,
            recorderUserId);
        return link;
    }

    /** The mother sees the baby under her portal's family access (the parent of a newborn). */
    private void grantMotherProxy(Patient baby, Patient mother, Locale locale) {
        if (mother.getUser() == null) {
            return;
        }
        boolean already = patientProxyRepository.findByGrantorPatient_IdAndProxyUser_IdAndStatus(
            baby.getId(), mother.getUser().getId(), ProxyStatus.ACTIVE).isPresent();
        if (already) {
            return;
        }
        patientProxyRepository.save(PatientProxy.builder()
            .grantorPatient(baby)
            .proxyUser(mother.getUser())
            .relationship(ProxyRelationship.PARENT)
            .status(ProxyStatus.ACTIVE)
            .permissions(PROXY_PERMISSIONS)
            .notes(messageSource.getMessage("newborn.registration.proxyNote", null, locale))
            .build());
    }

    /**
     * The creation is audited once the delivery commits, ids only (the
     * baby's row must exist before the audit row names it).
     */
    private void auditAfterCommit(UUID babyId, UUID hospitalId, String hospitalName, UUID deliveryId, int order,
                                  UUID recorderUserId) {
        String userName = HospitalContextHolder.getContextOrEmpty().getPrincipalUsername();
        AuditEventRequestDTO event = AuditEventRequestDTO.builder()
            .eventType(AuditEventType.PATIENT_CREATE)
            .status(AuditStatus.SUCCESS)
            .entityType(ENTITY_PATIENT)
            .resourceId(babyId.toString())
            .patientId(babyId)
            .hospitalId(hospitalId)
            .hospitalName(hospitalName)
            .userId(recorderUserId)
            .userName(userName)
            .eventDescription("Newborn registered from a recorded delivery")
            .details(Map.of("deliveryRecordId", deliveryId.toString(), "birthOrder", order))
            .build();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    audit(event);
                }
            });
        } else {
            audit(event);
        }
    }

    private void audit(AuditEventRequestDTO event) {
        try {
            auditEventLogService.logEvent(event);
        } catch (RuntimeException ex) {
            log.warn("Newborn registration not audited for patient {}: {}", event.getResourceId(),
                ex.getClass().getSimpleName());
        }
    }

    private DeliveryNewbornDTO toDto(DeliveryNewborn row) {
        Patient baby = row.getNewbornPatient();
        UUID hospitalId = row.getHospital() != null ? row.getHospital().getId() : null;
        String mrn = hospitalId == null ? null
            : registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(baby.getId(), hospitalId)
                .map(com.example.hms.model.PatientHospitalRegistration::getMrn)
                .orElse(null);
        return DeliveryNewbornDTO.builder()
            .patientId(baby.getId())
            .displayName(baby.getFullName())
            .mrn(mrn)
            .birthOrder(row.getBirthOrder())
            .needsReview(row.isNeedsReview())
            .reviewReason(row.getReviewReason())
            .build();
    }

    /** The registered sex for a recorded infant sex; undetermined or not recorded stays unknown (null). */
    static String genderOf(InfantSex sex) {
        if (sex == null) {
            return null;
        }
        return switch (sex) {
            case MALE -> "MALE";
            case FEMALE -> "FEMALE";
            case UNDETERMINED -> null;
        };
    }
}
