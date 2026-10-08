package com.revesoft.tms.push;

import com.revesoft.tms.license.Device;
import com.revesoft.tms.license.DeviceRepository;
import com.revesoft.tms.security.AuthUser;
import com.revesoft.tms.security.CurrentUser;
import com.revesoft.tms.security.TxRunner;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Registers app installations for push notifications. A token is unique across all organisations, so
 * registering it again (e.g. another account signs in on the same phone) moves it to the caller.
 */
@Service
public class PushService {

    private final PushTokenRepository tokens;
    private final DeviceRepository devices;
    private final TxRunner tx;

    public PushService(PushTokenRepository tokens, DeviceRepository devices, TxRunner tx) {
        this.tokens = tokens;
        this.devices = devices;
        this.tx = tx;
    }

    public record TokenRequest(@NotBlank @Size(max = 512) String token, @Size(max = 30) String platform) {
    }

    public record RemoveTokenRequest(@NotBlank @Size(max = 512) String token) {
    }

    public record TokenView(UUID id, String app, String platform) {
    }

    public TokenView register(TokenRequest r) {
        AuthUser me = CurrentUser.get();
        String token = r.token().trim();
        // A token registered in another organisation is released first (needs ROOT to see it).
        tx.asRoot(() -> {
            tokens.findByToken(token).filter(t -> !me.orgId().equals(t.getOrgId())).ifPresent(tokens::delete);
            return null;
        });
        return tx.inOrg(me.orgId(), () -> {
            PushToken t = tokens.findByToken(token).orElseGet(PushToken::new);
            t.setToken(token);
            t.setUserId(me.userId());
            t.setDeviceId(me.deviceId() != null && devices.existsById(me.deviceId()) ? me.deviceId() : null);
            t.setApp(me.isTenant() ? Device.App.TENANT : Device.App.ADMIN);
            if (r.platform() != null && !r.platform().isBlank()) {
                t.setPlatform(r.platform().trim());
            }
            tokens.save(t);
            return new TokenView(t.getId(), t.getApp().name(), t.getPlatform());
        });
    }

    /** Removes the caller's token (e.g. notifications turned off). Unknown tokens are ignored. */
    public void unregister(String token) {
        AuthUser me = CurrentUser.get();
        tx.inOrg(me.orgId(), () -> {
            tokens.findByToken(token.trim()).filter(t -> t.getUserId().equals(me.userId())).ifPresent(tokens::delete);
            return null;
        });
    }

    /** At logout: removes the token if it belongs to the user who is signing out. */
    public void unregisterForUser(UUID orgId, UUID userId, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        tx.inOrg(orgId, () -> {
            tokens.findByToken(token.trim()).filter(t -> t.getUserId().equals(userId)).ifPresent(tokens::delete);
            return null;
        });
    }
}
