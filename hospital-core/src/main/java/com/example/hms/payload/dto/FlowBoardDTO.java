package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowBoardDTO {
    private List<ReceptionQueueItemDTO> scheduled;
    private List<ReceptionQueueItemDTO> confirmed;
    private List<ReceptionQueueItemDTO> arrived;
    private List<ReceptionQueueItemDTO> inProgress;
    private List<ReceptionQueueItemDTO> noShow;
    private List<ReceptionQueueItemDTO> completed;
    private List<ReceptionQueueItemDTO> walkIn;
}
