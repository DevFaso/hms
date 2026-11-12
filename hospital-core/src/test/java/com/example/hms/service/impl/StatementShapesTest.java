package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StatementShapesTest {

    @Test
    void aStatementFromAnotherThreadDuringTheMeasurementIsNotCounted() throws InterruptedException {
        WardLoadPostgresIT.StatementShapes inspector = new WardLoadPostgresIT.StatementShapes();
        WardLoadPostgresIT.StatementShapes.begin();
        inspector.inspect("select * from clinical.mine");
        Thread scheduler = new Thread(() -> inspector.inspect("select * from security.revocations"));
        scheduler.start();
        scheduler.join();
        long counted = WardLoadPostgresIT.StatementShapes.end();

        assertThat(counted).isEqualTo(1);
        assertThat(WardLoadPostgresIT.StatementShapes.SEEN).containsOnlyKeys("clinical.mine");
    }

    @Test
    void nothingIsCountedOutsideAMeasurement() {
        WardLoadPostgresIT.StatementShapes inspector = new WardLoadPostgresIT.StatementShapes();
        WardLoadPostgresIT.StatementShapes.begin();
        WardLoadPostgresIT.StatementShapes.end();
        inspector.inspect("select 1 from x");
        assertThat(WardLoadPostgresIT.StatementShapes.end()).isZero();
    }
}
