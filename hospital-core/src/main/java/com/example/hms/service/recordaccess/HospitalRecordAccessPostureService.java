package com.example.hms.service.recordaccess;

import com.example.hms.enums.RecordAccessPosture;
import com.example.hms.payload.dto.recordaccess.RecordAccessPostureDTO;

import java.util.UUID;

/** E8 #52 — read and set a hospital's cross-hospital record-access posture. */
public interface HospitalRecordAccessPostureService {

    RecordAccessPostureDTO get(UUID hospitalId);

    /** Emits a {@code CONFIGURATION_CHANGED} audit row naming old and new posture. */
    RecordAccessPostureDTO set(UUID hospitalId, RecordAccessPosture posture, UUID actorUserId);
}
