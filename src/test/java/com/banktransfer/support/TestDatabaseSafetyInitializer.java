package com.banktransfer.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * 컨텍스트 refresh 이전, Hibernate DDL 이전에 테스트 데이터소스 안전을 검사한다.
 */
public final class TestDatabaseSafetyInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        Environment environment = applicationContext.getEnvironment();
        new TestDatabaseSafetyGuard(
                environment.getProperty("spring.datasource.url"),
                environment.getProperty("spring.data.redis.database"),
                System.getenv("DB_NAME"))
                .verify();
    }
}
