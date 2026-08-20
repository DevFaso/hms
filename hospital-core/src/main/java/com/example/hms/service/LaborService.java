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
}
