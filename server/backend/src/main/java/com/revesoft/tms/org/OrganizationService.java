package com.revesoft.tms.org;

import com.revesoft.tms.audit.AuditService;
import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.security.AccessGuard;
import com.revesoft.tms.security.TenantContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {

    private final OrganizationRepository repo;
    private final AccessGuard guard;
    private final AuditService audit;

    public OrganizationService(OrganizationRepository repo, AccessGuard guard, AuditService audit) {
        this.repo = repo;
        this.guard = guard;
        this.audit = audit;
    }

    public record OrgView(UUID id, String name, String contactName, String phone, String email, String address,
                          String status, Instant licenseExpiresAt, Integer maxUnits, Integer maxDevices,
                          String currency, int expiryAlertDays, boolean autoGenerateRent) {
        static OrgView of(Organization o) {
            return new OrgView(o.getId(), o.getName(), o.getContactName(), o.getPhone(), o.getEmail(), o.getAddress(),
                    o.getStatus().name(), o.getLicenseExpiresAt(), o.getMaxUnits(), o.getMaxDevices(),
                    o.getCurrency(), o.getExpiryAlertDays(), o.isAutoGenerateRent());
        }
    }

    public record SettingsRequest(@NotBlank @Size(max = 150) String name, String contactName, String phone,
                                  String email, String address, @NotBlank @Size(max = 10) String currency,
                                  @Min(1) @Max(365) int expiryAlertDays, boolean autoGenerateRent) {
    }

    /** The caller's organisation. */
    @Transactional(readOnly = true)
    public Organization current() {
        return repo.findById(TenantContext.requireOrg()).orElseThrow(() -> BusinessException.notFound("Organisation"));
    }

    @Transactional(readOnly = true)
    public OrgView view() {
        return OrgView.of(current());
    }

    @Transactional
    public OrgView updateSettings(SettingsRequest r) {
        guard.requireAdmin();
        Organization o = current();
        o.setName(r.name().trim());
        o.setContactName(r.contactName());
        o.setPhone(r.phone());
        o.setEmail(r.email());
        o.setAddress(r.address());
        o.setCurrency(r.currency().trim());
        o.setExpiryAlertDays(r.expiryAlertDays());
        o.setAutoGenerateRent(r.autoGenerateRent());
        audit.log("Updated Settings", "Organization", o.getId().toString(), o.getName());
        return OrgView.of(o);
    }
}
