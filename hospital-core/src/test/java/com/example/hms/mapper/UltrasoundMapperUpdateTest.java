package com.example.hms.mapper;

import com.example.hms.model.UltrasoundOrder;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderRequestDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D1672 class (r44 sweep): the ultrasound order edit dropped the scan count behind a success. */
class UltrasoundMapperUpdateTest {

    @Test
    void anEditedScanCountIsSaved() {
        UltrasoundOrder order = new UltrasoundOrder();
        order.setScanCountForPregnancy(1);
        UltrasoundOrderRequestDTO request = new UltrasoundOrderRequestDTO();
        request.setScanCountForPregnancy(3);

        new UltrasoundMapper().updateOrderFromRequest(order, request);

        assertThat(order.getScanCountForPregnancy()).isEqualTo(3);
    }
}
