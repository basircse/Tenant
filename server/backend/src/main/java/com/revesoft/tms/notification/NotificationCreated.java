package com.revesoft.tms.notification;

import java.util.UUID;

/** Published when an in-app notification is saved; delivered as a push after the transaction commits. */
public record NotificationCreated(UUID orgId, UUID notificationId, UUID userId, String title, String body) {
}
