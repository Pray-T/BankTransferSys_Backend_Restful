package com.banktransfer.support;

import java.util.Locale;

/**
 * 통합 테스트가 개발 데이터베이스에 붙은 채로 {@code ddl-auto=create-drop}을 실행하지 못하게 한다.
 * JDBC URL과 Redis 인덱스는 생성자로 받고, 접속은 열지 않는다.
 */
public final class TestDatabaseSafetyGuard {

    static final String DEV_DATABASE_NAME = "bank_TransferSys";

    static final String DEDICATED_TEST_DATABASE = "bank_transfer_test";

    private final String jdbcUrl;

    private final String redisDatabase;

    private final String dbNameEnvironment;

    public TestDatabaseSafetyGuard(String jdbcUrl, String redisDatabase, String dbNameEnvironment) {
        this.jdbcUrl = jdbcUrl;
        this.redisDatabase = redisDatabase;
        this.dbNameEnvironment = dbNameEnvironment;
    }

    public void verify() {
        String catalog = catalogOf(jdbcUrl);
        if (catalog.equalsIgnoreCase(DEV_DATABASE_NAME)) {
            throw new IllegalStateException(
                    "테스트 JDBC 카탈로그가 개발 데이터베이스 " + DEV_DATABASE_NAME + " 입니다. "
                            + "create-drop이 개발 데이터를 지우지 않도록 기동을 중단했습니다. "
                            + "DB_NAME은 테스트에서 읽지 않습니다. TEST_DB_NAME 기본값 "
                            + DEDICATED_TEST_DATABASE + " 를 사용하세요.");
        }
        if (namesOtherThanDedicatedTestDatabase(dbNameEnvironment)
                && catalog.equalsIgnoreCase(dbNameEnvironment.trim())) {
            throw new IllegalStateException(
                    "테스트 JDBC 카탈로그가 환경 변수 DB_NAME과 같습니다. "
                            + "DB_NAME이 " + DEDICATED_TEST_DATABASE + " 가 아니면 개발 스키마를 drop할 수 있어 기동을 중단했습니다. "
                            + "TEST_DB_NAME은 전용 테스트 DB(기본 " + DEDICATED_TEST_DATABASE + ")만 가리키게 하세요.");
        }
        if (!catalog.toLowerCase(Locale.ROOT).endsWith("_test")) {
            throw new IllegalStateException(
                    "테스트 데이터베이스 이름(" + catalog + ")이 _test로 끝나지 않습니다. "
                            + "create-drop은 전용 테스트 DB에서만 허용됩니다. TEST_DB_NAME을 확인하세요.");
        }
        verifyRedisDatabase();
    }

    static String catalogOf(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank() || jdbcUrl.contains("${")) {
            throw new IllegalStateException(
                    "테스트 JDBC URL(spring.datasource.url)을 해석하지 못했습니다. "
                            + "TEST_DB_NAME 기본값 " + DEDICATED_TEST_DATABASE + " 를 확인하세요.");
        }
        String withoutQuery = jdbcUrl;
        int query = jdbcUrl.indexOf('?');
        if (query >= 0) {
            withoutQuery = jdbcUrl.substring(0, query);
        }
        while (withoutQuery.endsWith("/")) {
            withoutQuery = withoutQuery.substring(0, withoutQuery.length() - 1);
        }
        int slash = withoutQuery.lastIndexOf('/');
        if (slash < 0 || slash == withoutQuery.length() - 1) {
            throw new IllegalStateException(
                    "테스트 JDBC URL에서 데이터베이스 이름을 읽지 못했습니다. "
                            + "TEST_DB_NAME 기본값 " + DEDICATED_TEST_DATABASE + " 를 확인하세요.");
        }
        String catalog = withoutQuery.substring(slash + 1).trim();
        if (catalog.isEmpty()) {
            throw new IllegalStateException(
                    "테스트 JDBC URL에서 데이터베이스 이름을 읽지 못했습니다. "
                            + "TEST_DB_NAME 기본값 " + DEDICATED_TEST_DATABASE + " 를 확인하세요.");
        }
        return catalog;
    }

    private void verifyRedisDatabase() {
        if (redisDatabase == null || redisDatabase.isBlank() || redisDatabase.contains("${")) {
            throw new IllegalStateException(
                    "테스트 Redis DB 인덱스가 비어 있습니다. 개발용 0번 DB를 쓰지 않도록 TEST_REDIS_DATABASE(기본 1)를 설정하세요.");
        }
        final int index;
        try {
            index = Integer.parseInt(redisDatabase.trim());
        }
        catch (NumberFormatException ex) {
            throw new IllegalStateException(
                    "테스트 Redis DB 인덱스를 정수로 읽지 못했습니다. TEST_REDIS_DATABASE는 0이 아닌 값을 사용하세요.");
        }
        if (index == 0) {
            throw new IllegalStateException(
                    "테스트가 Redis 0번 DB(개발용)에 붙습니다. 개발 키와 섞이지 않도록 TEST_REDIS_DATABASE를 1 이상으로 설정하세요.");
        }
        if (index < 0) {
            throw new IllegalStateException(
                    "테스트 Redis DB 인덱스가 올바르지 않습니다. TEST_REDIS_DATABASE는 1 이상을 사용하세요.");
        }
    }

    private static boolean namesOtherThanDedicatedTestDatabase(String dbName) {
        return dbName != null && !dbName.isBlank() && !DEDICATED_TEST_DATABASE.equals(dbName.trim());
    }
}
