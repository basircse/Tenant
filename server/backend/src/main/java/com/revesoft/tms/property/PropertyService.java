package com.revesoft.tms.property;

import com.revesoft.tms.agreement.Agreement;
import com.revesoft.tms.agreement.AgreementRepository;
import com.revesoft.tms.audit.AuditService;
import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.common.CodeGenerator;
import com.revesoft.tms.common.Money;
import com.revesoft.tms.license.LicenseService;
import com.revesoft.tms.security.AccessGuard;
import com.revesoft.tms.security.AuthUser;
import com.revesoft.tms.tenant.Tenant;
import com.revesoft.tms.tenant.TenantRepository;
import com.revesoft.tms.user.UserRepository;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Property & unit management (SRS §6, §7). */
@Service
public class PropertyService {

    private final PropertyRepository properties;
    private final UnitRepository units;
    private final AgreementRepository agreements;
    private final TenantRepository tenants;
    private final UserRepository users;
    private final CodeGenerator codes;
    private final AccessGuard guard;
    private final AuditService audit;
    private final LicenseService licenses;

    public PropertyService(PropertyRepository properties, UnitRepository units, AgreementRepository agreements,
                           TenantRepository tenants, UserRepository users, CodeGenerator codes, AccessGuard guard,
                           AuditService audit, LicenseService licenses) {
        this.properties = properties;
        this.units = units;
        this.agreements = agreements;
        this.tenants = tenants;
        this.users = users;
        this.codes = codes;
        this.guard = guard;
        this.audit = audit;
        this.licenses = licenses;
    }

    // ---------------------------------------------------------------- DTOs

    public record PropertyRequest(@NotBlank @Size(max = 150) String name, @NotNull Property.Type type,
                                  @NotBlank String address, @NotBlank @Size(max = 100) String city,
                                  String ownerName, String contactNumber, @Min(1) @Max(300) int floors,
                                  String description) {
    }

    public record PropertyView(UUID id, String code, String name, Property.Type type, String address, String city,
                               String ownerName, String contactNumber, int floors, String description,
                               Property.Status status, int units, int occupied, int vacant) {
    }

    public record PropertyDetail(PropertyView property, BigDecimal occupancyPercent, BigDecimal monthlyRentRoll,
                                 List<UnitView> units) {
    }

    public record UnitRequest(@NotBlank @Size(max = 20) String floor, @NotBlank @Size(max = 30) String unitNo,
                              String unitType, String size,
                              @NotNull @DecimalMin(value = "0.01", message = "must be greater than zero") BigDecimal monthlyRent,
                              @DecimalMin("0") BigDecimal serviceCharge, @DecimalMin("0") BigDecimal utilityCharge,
                              @DecimalMin("0") BigDecimal otherCharge, @DecimalMin("0") BigDecimal securityDeposit,
                              Unit.Status status) {
    }

    public record UnitView(UUID id, String code, UUID propertyId, String propertyName, String floor, String unitNo,
                           String unitType, String size, BigDecimal monthlyRent, BigDecimal serviceCharge,
                           BigDecimal utilityCharge, BigDecimal otherCharge, BigDecimal totalMonthly,
                           BigDecimal securityDeposit, Unit.Status status, UUID currentAgreementId,
                           UUID currentTenantId, String currentTenantName) {
    }

    // ---------------------------------------------------------------- Properties

    @Transactional(readOnly = true)
    public List<PropertyView> list(String q, Property.Type type, Property.Status status) {
        guard.requireStaff();
        Set<UUID> scope = guard.propertyScope();
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Map<UUID, List<Unit>> unitsByProperty = units.findAll().stream().collect(Collectors.groupingBy(Unit::getPropertyId));
        return properties.findAllByOrderByNameAsc().stream()
                .filter(p -> AccessGuard.inScope(scope, p.getId()))
                .filter(p -> type == null || p.getType() == type)
                .filter(p -> status == null || p.getStatus() == status)
                .filter(p -> query.isEmpty() || contains(query, p.getName(), p.getCode(), p.getAddress(), p.getCity(),
                        p.getOwnerName()))
                .map(p -> view(p, unitsByProperty.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PropertyDetail get(UUID id) {
        Property p = load(id);
        List<Unit> list = units.findByPropertyIdOrderByFloorAscUnitNoAsc(id);
        List<UnitView> unitViews = unitViews(list, Map.of(id, p));
        long active = list.stream().filter(u -> u.getStatus() != Unit.Status.INACTIVE).count();
        long occupied = list.stream().filter(u -> u.getStatus() == Unit.Status.OCCUPIED).count();
        BigDecimal occupancy = active == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(occupied * 100).divide(BigDecimal.valueOf(active), 1, RoundingMode.HALF_UP);
        BigDecimal rentRoll = agreements.findByPropertyIdAndStatusIn(id, Agreement.CURRENT).stream()
                .map(Agreement::totalMonthly).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PropertyDetail(view(p, list), occupancy, Money.of(rentRoll), unitViews);
    }

    @Transactional
    public PropertyDetail create(PropertyRequest r) {
        AuthUser me = guard.requireStaff();
        Property p = new Property();
        p.setCode(codes.next("P"));
        apply(p, r);
        properties.save(p);
        if (me.isManager()) {
            // A manager who creates a property is assigned to it automatically.
            users.findById(me.userId()).ifPresent(u -> u.getPropertyIds().add(p.getId()));
        }
        audit.log("Created Property", "Property", p.getCode(), p.getName());
        return get(p.getId());
    }

    @Transactional
    public PropertyDetail update(UUID id, PropertyRequest r) {
        Property p = load(id);
        apply(p, r);
        audit.log("Updated Property", "Property", p.getCode(), p.getName());
        return get(id);
    }

    @Transactional
    public PropertyDetail setStatus(UUID id, Property.Status status) {
        Property p = load(id);
        if (status == Property.Status.INACTIVE
                && units.findByPropertyIdOrderByFloorAscUnitNoAsc(id).stream()
                .anyMatch(u -> u.getStatus() == Unit.Status.OCCUPIED)) {
            throw new BusinessException("Cannot deactivate a property that has occupied units");
        }
        p.setStatus(status);
        audit.log(status == Property.Status.ACTIVE ? "Activated Property" : "Deactivated Property",
                "Property", p.getCode(), p.getName());
        return get(id);
    }

    // ---------------------------------------------------------------- Units

    @Transactional(readOnly = true)
    public List<UnitView> listUnits(UUID propertyId) {
        Property p = load(propertyId);
        return unitViews(units.findByPropertyIdOrderByFloorAscUnitNoAsc(propertyId), Map.of(p.getId(), p));
    }

    /** Every unit the caller may access, across properties (used by the apps to build their local cache). */
    @Transactional(readOnly = true)
    public List<UnitView> listAllUnits() {
        guard.requireStaff();
        Set<UUID> scope = guard.propertyScope();
        Map<UUID, Property> byId = properties.findAll().stream()
                .filter(p -> AccessGuard.inScope(scope, p.getId()))
                .collect(Collectors.toMap(Property::getId, p -> p));
        List<Unit> list = units.findAll().stream()
                .filter(u -> byId.containsKey(u.getPropertyId()))
                .sorted(java.util.Comparator.comparing((Unit u) -> byId.get(u.getPropertyId()).getName())
                        .thenComparing(Unit::getFloor).thenComparing(Unit::getUnitNo))
                .toList();
        return unitViews(list, byId);
    }

    @Transactional(readOnly = true)
    public UnitView getUnit(UUID unitId) {
        Unit u = loadUnit(unitId);
        return unitViews(List.of(u), Map.of(u.getPropertyId(), load(u.getPropertyId()))).get(0);
    }

    @Transactional
    public UnitView createUnit(UUID propertyId, UnitRequest r) {
        Property p = load(propertyId);
        Unit.Status requested = r.status() == null ? Unit.Status.AVAILABLE : r.status();
        if (requested != Unit.Status.INACTIVE) {
            licenses.checkUnitCapacity();
        }
        Unit u = new Unit();
        u.setPropertyId(p.getId());
        u.setCode(codes.next("U"));
        Unit.Status status = r.status() == null ? Unit.Status.AVAILABLE : r.status();
        if (status == Unit.Status.OCCUPIED) {
            throw new BusinessException("A unit becomes Occupied automatically when an agreement is activated");
        }
        apply(u, r, status);
        units.save(u);
        audit.log("Created Unit", "Unit", u.getCode(), p.getName() + " " + u.getUnitNo());
        return getUnit(u.getId());
    }

    @Transactional
    public UnitView updateUnit(UUID unitId, UnitRequest r) {
        Unit u = loadUnit(unitId);
        Unit.Status status = r.status() == null ? u.getStatus() : r.status();
        boolean occupied = u.getStatus() == Unit.Status.OCCUPIED;
        if (occupied != (status == Unit.Status.OCCUPIED)) {
            throw new BusinessException(occupied
                    ? "Unit is occupied. Terminate the agreement to change its status."
                    : "A unit becomes Occupied automatically when an agreement is activated");
        }
        if (u.getStatus() == Unit.Status.INACTIVE && status != Unit.Status.INACTIVE) {
            licenses.checkUnitCapacity();
        }
        apply(u, r, status);
        audit.log("Updated Unit", "Unit", u.getCode(), u.getUnitNo());
        return getUnit(unitId);
    }

    @Transactional
    public void deleteUnit(UUID unitId) {
        Unit u = loadUnit(unitId);
        if (agreements.existsByUnitId(unitId)) {
            throw BusinessException.conflict("Unit has agreement history; set it Inactive instead");
        }
        units.delete(u);
        audit.log("Deleted Unit", "Unit", u.getCode(), u.getUnitNo());
    }

    // ---------------------------------------------------------------- Helpers

    /** Loads a property the caller may access (staff only). */
    public Property load(UUID id) {
        guard.requireStaff();
        Property p = AccessGuard.owned(properties.findById(id), "Property");
        guard.checkProperty(p.getId());
        return p;
    }

    public Unit loadUnit(UUID id) {
        guard.requireStaff();
        Unit u = AccessGuard.owned(units.findById(id), "Unit");
        guard.checkProperty(u.getPropertyId());
        return u;
    }

    private void apply(Property p, PropertyRequest r) {
        p.setName(r.name().trim());
        p.setType(r.type());
        p.setAddress(r.address().trim());
        p.setCity(r.city().trim());
        p.setOwnerName(r.ownerName());
        p.setContactNumber(r.contactNumber());
        p.setFloors(r.floors());
        p.setDescription(r.description());
    }

    private void apply(Unit u, UnitRequest r, Unit.Status status) {
        String unitNo = r.unitNo().trim();
        if (units.existsByPropertyIdAndUnitNoIgnoreCaseAndIdNot(u.getPropertyId(), unitNo, u.getId())) {
            throw BusinessException.conflict("Unit " + unitNo + " already exists in this property");
        }
        u.setFloor(r.floor().trim());
        u.setUnitNo(unitNo);
        u.setUnitType(r.unitType());
        u.setSize(r.size());
        u.setMonthlyRent(Money.of(r.monthlyRent()));
        u.setServiceCharge(Money.of(r.serviceCharge()));
        u.setUtilityCharge(Money.of(r.utilityCharge()));
        u.setOtherCharge(Money.of(r.otherCharge()));
        u.setSecurityDeposit(Money.of(r.securityDeposit()));
        u.setStatus(status);
    }

    private static PropertyView view(Property p, List<Unit> list) {
        int occupied = (int) list.stream().filter(u -> u.getStatus() == Unit.Status.OCCUPIED).count();
        int vacant = (int) list.stream().filter(u -> u.getStatus() == Unit.Status.AVAILABLE).count();
        return new PropertyView(p.getId(), p.getCode(), p.getName(), p.getType(), p.getAddress(), p.getCity(),
                p.getOwnerName(), p.getContactNumber(), p.getFloors(), p.getDescription(), p.getStatus(),
                list.size(), occupied, vacant);
    }

    private List<UnitView> unitViews(List<Unit> list, Map<UUID, Property> props) {
        Map<UUID, Agreement> current = agreements.findByStatusIn(Agreement.CURRENT).stream()
                .collect(Collectors.toMap(Agreement::getUnitId, Function.identity(), (a, b) -> a));
        Map<UUID, Tenant> tenantById = tenants.findAllById(
                        current.values().stream().map(Agreement::getTenantId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Tenant::getId, Function.identity()));
        return list.stream().map(u -> {
            Agreement a = current.get(u.getId());
            Tenant t = a == null ? null : tenantById.get(a.getTenantId());
            Property p = props.get(u.getPropertyId());
            return new UnitView(u.getId(), u.getCode(), u.getPropertyId(), p == null ? null : p.getName(),
                    u.getFloor(), u.getUnitNo(), u.getUnitType(), u.getSize(), u.getMonthlyRent(),
                    u.getServiceCharge(), u.getUtilityCharge(), u.getOtherCharge(), u.totalMonthly(),
                    u.getSecurityDeposit(), u.getStatus(), a == null ? null : a.getId(),
                    t == null ? null : t.getId(), t == null ? null : t.getName());
        }).toList();
    }

    static boolean contains(String query, String... values) {
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }
}
