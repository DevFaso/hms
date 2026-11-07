package com.example.hms.service.support;

import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * The care guards are static, and Spring hands them their repositories at startup. A test context stays cached after
 * its class, with its container stopped, and the plain unit tests in the same JVM would then ask that dead repository.
 * So the bridges are put back after every Spring test class. A test that wants one sets it itself.
 */
public class ClinicalGuardBridgeResetListener extends AbstractTestExecutionListener {

    @Override
    public void afterTestClass(TestContext testContext) {
        DeceasedPatientGuard.useDisputes(null);
        OrderReviewHoldGuard.useHolds(null);
        OrderReviewHoldGuard.useReviewAgain(null);
    }
}
