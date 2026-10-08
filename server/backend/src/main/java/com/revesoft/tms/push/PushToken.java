package com.revesoft.tms.push;

import com.revesoft.tms.common.BaseEntity;
import com.revesoft.tms.license.Device;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A Firebase Cloud Messaging registration token of one app installation, bound to the signed-in user. */
@Getter
@Setter
@Entity
@Table(name = "push_tokens")
public class PushToken extends BaseEntity {

    @Column(nullable = false)
    private UUID userId;

    private UUID deviceId;

    @Column(nullable = false, updatable = false)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Device.App app;

    private String platform;
}
