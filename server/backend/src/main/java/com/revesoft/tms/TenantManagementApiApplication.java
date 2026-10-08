package com.revesoft.tms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TenantManagementApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(TenantManagementApiApplication.class, args);
    }
}
