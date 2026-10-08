package com.revesoft.tms;

import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The MySQL database used by the tests (see {@link LocalMysql}). One server and one fresh database
 * are shared by all test classes in the JVM; tests isolate themselves by creating their own
 * organisations.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EmbeddedDb {

    private static DataSource dataSource;

    static synchronized DataSource instance() {
        if (dataSource == null) {
            dataSource = LocalMysql.dataSource("tms_test", Path.of(System.getProperty("user.home"), ".tms", "test-mysql"), 0, true);
        }
        return dataSource;
    }

    // Shared by every test context in the JVM: Spring must not close it with the first context.
    @Bean(destroyMethod = "")
    @Primary
    DataSource dataSource() {
        return instance();
    }
}
