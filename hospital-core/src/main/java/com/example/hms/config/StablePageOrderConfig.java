package com.example.hms.config;

import com.example.hms.utility.StablePageOrder;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;

/**
 * QA D1361 (round 31): the choke point every paged repository read goes
 * through. Each Spring Data repository proxy gets one more advice, in front
 * of query execution, that replaces the call's {@link Pageable} with
 * {@link StablePageOrder#forQuery}: an order whose last key is the unique
 * {@code id}. Paging through any list - a portal {@code allPages} read, a
 * worklist, an export - can then neither repeat a row on two pages nor skip
 * one, whichever service or controller built the page.
 */
@Configuration(proxyBeanMethods = false)
public class StablePageOrderConfig {

    /**
     * Only a static bean method (a BeanPostProcessor is declared static so it
     * does not instantiate this class early). With {@code proxyBeanMethods =
     * false} there is no CGLIB subclass, and Spring instantiates a
     * configuration class through a private constructor (Sonar S1118).
     */
    private StablePageOrderConfig() {
    }

    @Bean
    static BeanPostProcessor stablePageOrderRepositories() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof RepositoryFactoryBeanSupport<?, ?, ?> factoryBean) {
                    factoryBean.addRepositoryFactoryCustomizer(factory -> factory.addRepositoryProxyPostProcessor(
                        (proxy, information) -> proxy.addAdvice(new StablePageAdvice(information.getDomainType()))));
                }
                return bean;
            }
        };
    }

    /** Replaces each {@link Pageable} argument with its stable form. */
    static final class StablePageAdvice implements MethodInterceptor {

        private final Class<?> domainType;

        StablePageAdvice(Class<?> domainType) {
            this.domainType = domainType;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            Object[] arguments = invocation.getArguments();
            for (int i = 0; i < arguments.length; i++) {
                if (arguments[i] instanceof Pageable pageable) {
                    arguments[i] = StablePageOrder.forQuery(pageable, domainType, invocation.getMethod());
                }
            }
            return invocation.proceed();
        }
    }
}
