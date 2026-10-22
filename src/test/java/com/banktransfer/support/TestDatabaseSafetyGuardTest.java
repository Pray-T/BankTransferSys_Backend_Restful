package com.banktransfer.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class TestDatabaseSafetyGuardTest {

    @Test
    @DisplayName("DB_NAME이 개발 스키마여도 테스트 URL 카탈로그는 bank_transfer_test이다")
    void resolvedCatalogIgnoresDbNameEnvironment() throws IOException {
        Properties file = loadApplicationTestProperties();
        String urlTemplate = file.getProperty("spring.datasource.url");
        String redisTemplate = file.getProperty("spring.data.redis.database");

        assertThat(urlTemplate).contains("${TEST_DB_NAME:bank_transfer_test}");
        assertThat(urlTemplate).doesNotContain("${DB_NAME");
        assertThat(redisTemplate).isEqualTo("${TEST_REDIS_DATABASE:1}");
        assertThat(file.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("create-drop");

        MockEnvironment environment = new MockEnvironment()
                .withProperty("DB_NAME", "bank_TransferSys")
                .withProperty("DB_HOST", "127.0.0.1")
                .withProperty("DB_PORT", "3306")
                .withProperty("REDIS_DATABASE", "0")
                .withProperty("spring.datasource.url", urlTemplate)
                .withProperty("spring.data.redis.database", redisTemplate);

        String resolvedUrl = environment.getProperty("spring.datasource.url");
        String resolvedRedis = environment.getProperty("spring.data.redis.database");
        String catalog = TestDatabaseSafetyGuard.catalogOf(resolvedUrl);

        assertThat(catalog)
                .as("DB_NAME=bank_TransferSys must not change the test catalog")
                .isEqualTo("bank_transfer_test");
        assertThat(resolvedRedis).isEqualTo("1");
        assertThat(resolvedUrl).doesNotContain("bank_TransferSys");
        new TestDatabaseSafetyGuard(resolvedUrl, resolvedRedis, "bank_TransferSys").verify();
    }

    @Test
    @DisplayName("쿼리스트링이 있는 전용 테스트 DB는 DB_NAME과 달라도 통과한다")
    void dedicatedTestDatabaseWithQueryPasses() {
        assertThatCode(() -> guard(
                "jdbc:mysql://127.0.0.1:3306/bank_transfer_test?x=y",
                "1",
                "bank_TransferSys").verify())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("쿼리스트링이 없는 전용 테스트 DB도 통과한다")
    void dedicatedTestDatabaseWithoutQueryPasses() {
        assertThatCode(() -> guard(
                "jdbc:mysql://127.0.0.1:3306/bank_transfer_test",
                "1",
                null).verify())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DB_NAME이 정확히 bank_transfer_test이면 그 카탈로그는 허용한다")
    void exactDedicatedDbNameIsAllowed() {
        assertThatCode(() -> guard(
                "jdbc:mysql://127.0.0.1:3306/bank_transfer_test?createDatabaseIfNotExist=true",
                "1",
                "bank_transfer_test").verify())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("개발 DB 이름이면 쿼리스트링 유무와 대소문자에 상관없이 실패한다")
    void devDatabaseNameFails() {
        assertRejected("jdbc:mysql://127.0.0.1:3306/bank_TransferSys?useSSL=false", "1", null, "bank_TransferSys");
        assertRejected("jdbc:mysql://127.0.0.1:3306/bank_TransferSys", "1", "bank_TransferSys", "bank_TransferSys");
        assertRejected("jdbc:mysql://127.0.0.1:3306/BANK_TRANSFERSYS?x=y", "1", null, "bank_TransferSys");
        assertRejected("jdbc:mysql://127.0.0.1:3306/bank_transfersys", "1", null, "bank_TransferSys");
    }

    @Test
    @DisplayName("카탈로그가 bank_transfer_test가 아닌 DB_NAME과 같으면 실패한다")
    void catalogMatchingForeignDbNameFails() {
        assertRejected(
                "jdbc:mysql://127.0.0.1:3306/custom_test?x=y",
                "1",
                "custom_test",
                "DB_NAME");
        assertRejected(
                "jdbc:mysql://127.0.0.1:3306/my_app",
                "1",
                "my_app",
                "DB_NAME");
    }

    @Test
    @DisplayName("_test로 끝나지 않는 카탈로그는 실패한다")
    void catalogWithoutTestSuffixFails() {
        assertRejected("jdbc:mysql://127.0.0.1:3306/bank_transfer?x=y", "1", null, "_test");
    }

    @Test
    @DisplayName("Redis 0번은 실패하고 1번은 통과한다")
    void redisDatabaseZeroFailsAndOnePasses() {
        assertRejected(
                "jdbc:mysql://127.0.0.1:3306/bank_transfer_test?x=y",
                "0",
                "bank_TransferSys",
                "TEST_REDIS_DATABASE");
        assertThatCode(() -> guard(
                "jdbc:mysql://127.0.0.1:3306/bank_transfer_test?x=y",
                "1",
                "bank_TransferSys").verify())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TEST_DB_NAME을 개발 DB로 해석하면 컨텍스트 refresh 전에 실패한다")
    void initializerRejectsDevCatalogBeforeRefresh() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "simulated-test-url",
                    Map.of(
                            "spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/bank_TransferSys?useSSL=false",
                            "spring.data.redis.database", "1")));
            assertThat(context.isActive()).isFalse();
            assertThatThrownBy(() -> new TestDatabaseSafetyInitializer().initialize(context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bank_TransferSys");
            assertThat(context.isActive()).isFalse();
        }
    }

    private static TestDatabaseSafetyGuard guard(String jdbcUrl, String redisDatabase, String dbNameEnvironment) {
        return new TestDatabaseSafetyGuard(jdbcUrl, redisDatabase, dbNameEnvironment);
    }

    private static void assertRejected(String jdbcUrl, String redisDatabase, String dbNameEnvironment, String messagePart) {
        assertThatThrownBy(() -> guard(jdbcUrl, redisDatabase, dbNameEnvironment).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(messagePart);
    }

    private static Properties loadApplicationTestProperties() throws IOException {
        Properties file = new Properties();
        try (InputStream in = TestDatabaseSafetyGuardTest.class.getClassLoader()
                .getResourceAsStream("application-test.properties")) {
            assertThat(in).isNotNull();
            file.load(in);
        }
        return file;
    }
}
