package com.example.hms.service.platform.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.MllpAllowedSenderMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.platform.MllpAllowedSender;
import com.example.hms.payload.dto.platform.MllpAllowedSenderRequestDTO;
import com.example.hms.payload.dto.platform.MllpAllowedSenderResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.platform.MllpAllowedSenderRepository;
import com.example.hms.service.platform.MllpAllowedSenderService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class MllpAllowedSenderServiceImpl implements MllpAllowedSenderService {

    private static final String SENDER_NOT_FOUND = "mllp.allowedsender.notfound";
    private static final String HOSPITAL_NOT_FOUND = "hospital.notfound";

    private final MllpAllowedSenderRepository senderRepository;
    private final HospitalRepository hospitalRepository;
    private final MllpAllowedSenderMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<Hospital> resolveHospital(String sendingApplication, String sendingFacility) {
        return lookup(sendingApplication, sendingFacility)
            .map(MllpAllowedSender::getHospital)
            .map(MllpAllowedSenderServiceImpl::initialiseForUseAfterThisTransaction);
    }

    /**
     * Everything an MLLP caller reads off this hospital, read here, while
     * there is still a session to read it in.
     *
     * <p>{@code MllpAllowedSender.hospital} is LAZY and so is
     * {@code Hospital.organization}, both entities take their identifier from
     * a field-access {@code @Id} in {@code BaseEntity}, and
     * {@code open-in-view} is false. So {@code getId()} on either one is a
     * proxy initialisation, and every caller of this method runs on an MLLP
     * worker thread after this read-only transaction has closed — a different
     * session, which does not re-attach a detached proxy.
     * {@link #resolveHospitalId} exists because of exactly that, and the
     * inbound services need the organization for the same reason: it is what
     * an {@code integration_message_event} row is filed under, so without
     * this every rejection row would land with a null organization and the
     * per-organization DLQ view would never show one.
     *
     * <p>Initialising is cheap — one extra select on a lookup that is already
     * cached per message — and it removes the trap rather than documenting it.
     * {@code Hibernate.initialize(null)} is a no-op, so a hospital with no
     * organization needs no special case.
     */
    private static Hospital initialiseForUseAfterThisTransaction(Hospital hospital) {
        Hibernate.initialize(hospital);
        Hibernate.initialize(hospital.getOrganization());
        return hospital;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> resolveHospitalId(String sendingApplication, String sendingFacility) {
        // The same lookup as resolveHospital, written out rather than
        // delegating to it: a self-invocation does not pass through the proxy,
        // so the annotation on the method being called would not apply and the
        // reader has to know that this method's own annotation is what covers
        // it. Reading the identifier INSIDE the transaction is the whole point
        // here - Hospital maps @Id on a field, so getId() initialises the
        // proxy, which is a LazyInitializationException once the transaction
        // has closed.
        return lookup(sendingApplication, sendingFacility).map(s -> s.getHospital().getId());
    }

    /** The allowlist row for a sending pair, normalised as the column stores it. */
    private Optional<MllpAllowedSender> lookup(String sendingApplication, String sendingFacility) {
        if (!StringUtils.hasText(sendingApplication) || !StringUtils.hasText(sendingFacility)) {
            return Optional.empty();
        }
        // Stored values are normalised to upper-case canonical form
        // (V62 CHECK constraints + MllpAllowedSenderMapper). Match that
        // normalisation here so the case-sensitive index can be used directly.
        return senderRepository.findBySendingApplicationAndSendingFacilityAndActiveTrue(
            sendingApplication.trim().toUpperCase(java.util.Locale.ROOT),
            sendingFacility.trim().toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    @Transactional
    public MllpAllowedSenderResponseDTO create(MllpAllowedSenderRequestDTO request, Locale locale) {
        Hospital hospital = loadHospital(request.hospitalId());
        MllpAllowedSender entity = mapper.toEntity(request, hospital);
        MllpAllowedSender saved = senderRepository.save(entity);
        log.info("MLLP allowed sender created id={} app={} facility={} hospital={}",
            saved.getId(), saved.getSendingApplication(), saved.getSendingFacility(), hospital.getId());
        return mapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public MllpAllowedSenderResponseDTO update(UUID id, MllpAllowedSenderRequestDTO request, Locale locale) {
        MllpAllowedSender entity = senderRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(SENDER_NOT_FOUND, id));
        Hospital hospital = loadHospital(request.hospitalId());
        mapper.applyToEntity(request, hospital, entity);
        MllpAllowedSender saved = senderRepository.save(entity);
        log.info("MLLP allowed sender updated id={} active={}", saved.getId(), saved.isActive());
        return mapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public MllpAllowedSenderResponseDTO getById(UUID id, Locale locale) {
        return senderRepository.findById(id)
            .map(mapper::toResponseDTO)
            .orElseThrow(() -> new ResourceNotFoundException(SENDER_NOT_FOUND, id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MllpAllowedSenderResponseDTO> findAll(Locale locale) {
        return senderRepository.findAllByOrderBySendingFacilityAscSendingApplicationAsc()
            .stream()
            .map(mapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MllpAllowedSenderResponseDTO> findByHospital(UUID hospitalId, Locale locale) {
        return senderRepository.findAllByHospital_IdOrderBySendingFacilityAsc(hospitalId)
            .stream()
            .map(mapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public void deactivate(UUID id, Locale locale) {
        MllpAllowedSender entity = senderRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(SENDER_NOT_FOUND, id));
        if (entity.isActive()) {
            entity.setActive(false);
            senderRepository.save(entity);
            log.info("MLLP allowed sender deactivated id={}", id);
        }
    }

    private Hospital loadHospital(UUID hospitalId) {
        return hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND, hospitalId));
    }
}
