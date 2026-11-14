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
 * registered again. A stillborn baby is never registered (QA D1714: it creates
 * a fetal-death event instead), so mixed twins register the live one. A count that falls below
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
    private final com.example.hms.repository.DeliveryRecordRepository deliveryRecordRepository;
    private final PatientAccountRegistrationService patientAccountRegistrationService;
    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final PatientProxyRepository patientProxyRepository;
    private final MessageSource messageSource;
    private final AuditEventLogService auditEventLogService;
    /** QA D1592: reception is told when a baby could not be registered. */
    private final com.example.hms.service.InBasketService inBasketService;
    private final com.example.hms.repository.UserRoleHospitalAssignmentRepository assignmentRepository;

    /** The in-basket reference type of a delivery whose babies wait for registration. */
    static final String PENDING_REFERENCE_TYPE = "NEWBORN_REGISTRATION";
    static final String PENDING_TITLE_KEY = "newborn.registration.pending.title";
    static final String PENDING_BODY_KEY = "newborn.registration.pending.body";
    /**
     * Who registers a patient at the delivering hospital: reception. Not the
     * hospital administrator: the dispute review is the one in-basket item
     * an administrator reads (E9 #67, InBasketRecipientGuardTest).
     */
    static final java.util.Set<String> PENDING_ROLE_CODES = java.util.Set.of(
        com.example.hms.config.SecurityConstants.ROLE_RECEPTIONIST);

    /**
     * Register the live-born babies of a saved delivery that are not yet
     * registered, and flag any registered baby the record no longer counts.
     *
     * @return every baby registered for this delivery, in birth order
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public List<DeliveryNewbornDTO> registerLiveBorn(DeliveryRecord recorded, UUID recorderUserId) {
        // Its own transaction, after the delivery committed: the committed row.
        DeliveryRecord delivery = recorded.getId() == null ? recorded
            : deliveryRecordRepository.findById(recorded.getId()).orElse(recorded);
        List<DeliveryNewborn> existing = new ArrayList<>(
            deliveryNewbornRepository.findByDeliveryRecord_IdOrderByBirthOrderAsc(delivery.getId()));
        // QA D1714: one outcome per baby. Only the live-born babies are registered (mixed twins: the live one).
        java.util.Set<Integer> liveOrders = new java.util.TreeSet<>();
        for (int order : delivery.liveOrders()) {
            liveOrders.add(order);
        }
        int count = Math.max(delivery.getNumberOfInfants(), 1);

        for (DeliveryNewborn row : existing) {
            if (!liveOrders.contains(row.getBirthOrder()) && !row.isNeedsReview()) {
                row.setNeedsReview(true);
                row.setReviewReason(delivery.isLiveBirth() && row.getBirthOrder() > count
                    ? REVIEW_COUNT_LOWERED : REVIEW_NOT_LIVE_BIRTH);
                deliveryNewbornRepository.save(row);
                log.warn("Delivery {} no longer counts newborn {}: flagged for review", delivery.getId(), row.getId());
            }
        }

        java.util.Set<Integer> registeredOrders = new java.util.HashSet<>();
        existing.forEach(row -> registeredOrders.add(row.getBirthOrder()));
        for (int order : liveOrders) {
            if (!registeredOrders.contains(order)) {
                existing.add(registerOne(delivery, order, count, recorderUserId));
            }
        }
        existing.sort(java.util.Comparator.comparingInt(DeliveryNewborn::getBirthOrder));
        // The row was read in this transaction: the change is flushed at its commit.
        if (delivery.isNewbornRegistrationPending() && delivery.getId() != null) {
            // The reception item filed when an earlier attempt failed is done.
            inBasketService.removeAllItemsForReference(delivery.getId(), PENDING_REFERENCE_TYPE);
        }
        delivery.setNewbornRegistrationPending(false);
        return existing.stream().map(this::toDto).toList();
    }

    /**
     * QA D1592: a registration that failed is audited (ids and the error
     * class only); the delivery keeps its pending flag for reception. Never
     * throws.
     */
    public void recordRegistrationFailure(DeliveryRecord delivery, UUID recorderUserId, RuntimeException cause) {
        try {
            fileReceptionItems(delivery);
        } catch (RuntimeException ex) {
            log.warn("Reception not told of the pending newborns of delivery {}: {}", delivery.getId(),
                ex.getClass().getSimpleName());
        }
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.PATIENT_CREATE)
                .status(AuditStatus.FAILURE)
                .entityType("DELIVERY_RECORD")
                .resourceId(delivery.getId() == null ? null : delivery.getId().toString())
                .hospitalId(delivery.getHospital() == null ? null : delivery.getHospital().getId())
                .userId(recorderUserId)
                .userName(HospitalContextHolder.getContextOrEmpty().getPrincipalUsername())
                .eventDescription("Newborn registration pending after a recorded delivery")
                .details(Map.of("error", cause == null ? "unknown" : cause.getClass().getSimpleName()))
                .build());
        } catch (RuntimeException ex) {
            log.warn("Newborn registration failure not audited for delivery {}: {}", delivery.getId(),
                ex.getClass().getSimpleName());
        }
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

    /**
     * QA D1592: one in-basket TASK per receptionist of the
     * delivering hospital (that hospital only), deduplicated per delivery and
     * reader by the in-basket itself; removed when the babies are registered.
     */
    private void fileReceptionItems(DeliveryRecord delivery) {
        if (delivery.getId() == null || delivery.getHospital() == null) {
            return;
        }
        UUID hospitalId = delivery.getHospital().getId();
        Patient mother = delivery.getPatient();
        String motherName = mother == null ? null : mother.getFullName();
        Object[] bodyArgs = {motherName};
        for (com.example.hms.model.User reader
            : assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(hospitalId, PENDING_ROLE_CODES)) {
            com.example.hms.payload.dto.clinical.CreateInBasketItemRequest item =
                new com.example.hms.payload.dto.clinical.CreateInBasketItemRequest();
            item.setRecipientUserId(reader.getId());
            item.setHospitalId(hospitalId);
            item.setItemType(com.example.hms.enums.InBasketItemType.TASK);
            item.setPriority(com.example.hms.enums.InBasketPriority.URGENT);
            item.setReferenceId(delivery.getId());
            item.setReferenceType(PENDING_REFERENCE_TYPE);
            item.setPatientId(mother == null ? null : mother.getId());
            item.setPatientName(motherName);
            item.setTitleKey(PENDING_TITLE_KEY);
            item.setTitle(messageSource.getMessage(PENDING_TITLE_KEY, null, NotificationLocales.STAFF));
            item.setBodyKey(PENDING_BODY_KEY);
            item.setBodyArgs(bodyArgs);
            item.setBody(messageSource.getMessage(PENDING_BODY_KEY, bodyArgs, NotificationLocales.STAFF));
            // Each item in its own transaction: the delivery has committed already.
            inBasketService.createItemInNewTransaction(item);
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
