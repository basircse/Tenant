package com.revesoft.tms.user;

import com.revesoft.tms.user.UserService.BlockRequest;
import com.revesoft.tms.user.UserService.TempPassword;
import com.revesoft.tms.user.UserService.UserRequest;
import com.revesoft.tms.user.UserService.UserView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    public record StatusRequest(@NotNull AppUser.Status status) {
    }

    @GetMapping
    public List<UserView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public UserView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody UserRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable UUID id, @Valid @RequestBody UserRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/status")
    public UserView setStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return service.setStatus(id, request.status());
    }

    /** Blocks a user of this organisation: signed out at once and unable to sign in. */
    @PostMapping("/{id}/block")
    public UserView block(@PathVariable UUID id, @Valid @RequestBody BlockRequest request) {
        return service.block(id, request.reason());
    }

    @PostMapping("/{id}/unblock")
    public UserView unblock(@PathVariable UUID id) {
        return service.unblock(id);
    }

    @PostMapping("/{id}/reset-password")
    public TempPassword resetPassword(@PathVariable UUID id) {
        return service.resetPassword(id);
    }
}
