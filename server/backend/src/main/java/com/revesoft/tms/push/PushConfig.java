package com.revesoft.tms.push;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Push is optional: with {@code tms.push.fcm-credentials} (env {@code FCM_CREDENTIALS_FILE}) pointing to a
 * Firebase service-account JSON, notifications go out through FCM; otherwise they are only logged.
 */
@Configuration(proxyBeanMethods = false)
public class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

    @Bean
    PushSender pushSender(@Value("${tms.push.fcm-credentials:}") String credentials) {
        if (credentials == null || credentials.isBlank()) {
            log.info("Push notifications: FCM not configured (set FCM_CREDENTIALS_FILE); pushes are only logged");
            return new LoggingPushSender();
        }
        Path file = Path.of(credentials.trim());
        if (!Files.isReadable(file)) {
            throw new IllegalStateException("FCM_CREDENTIALS_FILE not found or not readable: " + file.toAbsolutePath());
        }
        return FcmPushSender.fromServiceAccount(file);
    }
}
