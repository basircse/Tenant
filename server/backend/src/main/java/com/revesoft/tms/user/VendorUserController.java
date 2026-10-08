package com.revesoft.tms.user;

import com.revesoft.tms.user.UserService.BlockRequest;
import com.revesoft.tms.user.UserService.VendorUserView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The vendor's view of every user in every organisation, with blocking. */
@RestController
@RequestMapping("/api/vendor/users")
@PreAuthorize("hasRole('VENDOR')")
public class VendorUserController {

    private final UserService service;

    public VendorUserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    public List<VendorUserView> list(@RequestParam(required = false) UUID org,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) AppUser.Status status) {
        return service.listAll(org, q, status);
    }

    /** Blocks any user; the organisation's admins cannot lift a block placed here. */
    @PostMapping("/{id}/block")
    public VendorUserView block(@PathVariable UUID id, @Valid @RequestBody BlockRequest request) {
        return service.blockAny(id, request.reason());
    }

    @PostMapping("/{id}/unblock")
    public VendorUserView unblock(@PathVariable UUID id) {
        return service.unblockAny(id);
    }
}
