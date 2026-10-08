package com.revesoft.tms.push;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Delivers push notifications to app installations (Firebase Cloud Messaging, or a log in development). */
public interface PushSender {

    record PushMessage(String title, String body, Map<String, String> data) {
    }

    /**
     * Sends {@code message} to every token. Never throws for individual delivery failures.
     *
     * @return tokens that are no longer valid (unregistered app, wrong project) and should be deleted
     */
    Set<String> send(List<String> tokens, PushMessage message);
}
