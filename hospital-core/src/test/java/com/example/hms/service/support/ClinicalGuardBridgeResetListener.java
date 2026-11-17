package com.example.hms.service.support;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import java.util.List;

/**
 * The care guards are static, and Spring hands them their repositories at startup. A test context stays cached after
 * its class, with its container stopped, and the plain unit tests in the same JVM would then ask that dead repository.
 * So the bridges are put back after every Spring test class, and a class reusing that cached context takes them up
 * again before its tests: without that, a guard would fail open in it and every "not refused" would pass for nothing.
 */
public class ClinicalGuardBridgeResetListener extends AbstractTestExecutionListener {

    /** After the context loads, so an IT's container is up. A slice with none of these beans has no bridge to set. */
    @Override
    public void prepareTestInstance(TestContext testContext) throws Exception {
        var context = testContext.getApplicationContext();
        for (Class<? extends InitializingBean> bridge : List.of(DeathCareEligibility.class, OrderReviewHoldGuard.class,
                com.example.hms.service.impl.OrderReviewHoldServiceImpl.class)) {
            InitializingBean bean = context.getBeanProvider(bridge).getIfAvailable();
            if (bean != null) {
                bean.afterPropertiesSet();
            }
        }
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        DeceasedPatientGuard.useDisputes(null);
        OrderReviewHoldGuard.useHolds(null);
        OrderReviewHoldGuard.useReview(null);
    }
}
