package com.example.hms.service;

import com.example.hms.payload.dto.clinical.labor.DeliveryRecordRequestDTO;
import com.example.hms.payload.dto.clinical.labor.DeliveryRecordResponseDTO;
import com.example.hms.payload.dto.clinical.labor.LaborEpisodeRequestDTO;
import com.example.hms.payload.dto.clinical.labor.LaborEpisodeResponseDTO;
import com.example.hms.payload.dto.clinical.labor.PartographEntryRequestDTO;
import com.example.hms.payload.dto.clinical.labor.PartographEntryResponseDTO;

import java.util.List;
import java.util.UUID;

/** Labor & Delivery: episodes, WHO partograph entries, delivery records (P1 #6). */
public interface LaborService {

    LaborEpisodeResponseDTO startEpisode(UUID patientId, UUID recorderUserId, LaborEpisodeRequestDTO request);

    List<LaborEpisodeResponseDTO> getEpisodes(UUID patientId, UUID hospitalId, int limit);

    PartographEntryResponseDTO addEntry(UUID patientId, UUID episodeId, UUID recorderUserId,
                                        PartographEntryRequestDTO request);

    List<PartographEntryResponseDTO> getEntries(UUID patientId, UUID episodeId, UUID hospitalId);

    DeliveryRecordResponseDTO recordDelivery(UUID patientId, UUID episodeId, UUID recorderUserId,
                                             DeliveryRecordRequestDTO request);

    DeliveryRecordResponseDTO getDelivery(UUID patientId, UUID episodeId, UUID hospitalId);

    /** QA D1714: correct one baby's recorded outcome; the fetal-death event is superseded or created, never deleted. */
    DeliveryRecordResponseDTO correctBabyOutcome(UUID patientId, UUID episodeId, int birthOrder, UUID userId,
        com.example.hms.payload.dto.clinical.labor.BabyOutcomeCorrectionRequestDTO request);

    /**
     * The episode's own hospital hands an active labour to another hospital
     * (provisional rule, 2026-11-12): this episode closes as TRANSFERRED_OUT
     * and the destination gets a new active episode linked to it.
     */
    LaborEpisodeResponseDTO transferEpisode(UUID patientId, UUID episodeId, UUID callerUserId,
                                            com.example.hms.payload.dto.clinical.labor.LaborTransferRequestDTO request);

    /**
     * She arrived at the acting hospital in labour without a handover: the
     * other hospital's active episode closes as LEFT_WITHOUT_HANDOVER and the
     * acting hospital opens its own, linked to it.
     */
    LaborEpisodeResponseDTO recordArrivalWithoutHandover(UUID patientId, UUID sourceEpisodeId, UUID callerUserId,
                                                         com.example.hms.payload.dto.clinical.labor.LaborArrivalRequestDTO request);
}
