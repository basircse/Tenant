package com.revesoft.tms;

import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Runs the API on http://localhost:8080 with a private local MySQL (see {@link LocalMysql}; no
 * installation or Docker needed). Data is kept in {@code backend/.dev-mysql} between runs and the
 * server listens on 127.0.0.1:3307.
 *
 * <pre>mvn spring-boot:test-run -Dspring-boot.run.mainClass=com.revesoft.tms.LocalDevApplication</pre>
 */
public class LocalDevApplication {

    public static void main(String[] args) {
        // Local-only vendor account for trying the vendor console.
        System.setProperty("tms.vendor.username", System.getProperty("tms.vendor.username", "vendor"));
        System.setProperty("tms.vendor.password", System.getProperty("tms.vendor.password", "Vendor1234"));
        SpringApplication.from(TenantManagementApiApplication::main).with(LocalDevDb.class).run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalDevDb {

        @Bean
        DataSource dataSource() {
            return LocalMysql.dataSource("tms", Path.of(".dev-mysql"), 3307, false);
        }
    }
}
