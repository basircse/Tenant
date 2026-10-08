package com.revesoft.tms.push;

import com.revesoft.tms.push.PushService.RemoveTokenRequest;
import com.revesoft.tms.push.PushService.TokenRequest;
import com.revesoft.tms.push.PushService.TokenView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Push notification registration for the signed-in user (all roles, both apps). */
@RestController
@RequestMapping("/api/push")
public class PushController {

    private final PushService service;

    public PushController(PushService service) {
        this.service = service;
    }

    /** Call after sign-in and whenever Firebase reports a new token. */
    @PostMapping("/token")
    public TokenView register(@Valid @RequestBody TokenRequest request) {
        return service.register(request);
    }

    @DeleteMapping("/token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@Valid @RequestBody RemoveTokenRequest request) {
        service.unregister(request.token());
    }
}
