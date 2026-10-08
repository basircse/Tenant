package com.revesoft.tms.common;

import com.revesoft.tms.property.Property;
import com.revesoft.tms.property.PropertyRepository;
import com.revesoft.tms.property.Unit;
import com.revesoft.tms.property.UnitRepository;
import com.revesoft.tms.tenant.Tenant;
import com.revesoft.tms.tenant.TenantRepository;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Batch-loads display names for tenants, units and properties to build list responses. */
@Component
public class NameLookup {

    private final TenantRepository tenants;
    private final UnitRepository units;
    private final PropertyRepository properties;

    public NameLookup(TenantRepository tenants, UnitRepository units, PropertyRepository properties) {
        this.tenants = tenants;
        this.units = units;
        this.properties = properties;
    }

    public record Names(Map<UUID, Tenant> tenants, Map<UUID, Unit> units, Map<UUID, Property> properties) {

        public String tenant(UUID id) {
            Tenant t = tenants.get(id);
            return t == null ? null : t.getName();
        }

        public String unit(UUID id) {
            Unit u = units.get(id);
            return u == null ? null : u.getUnitNo();
        }

        public String property(UUID id) {
            Property p = properties.get(id);
            return p == null ? null : p.getName();
        }
    }

    public Names load(Collection<UUID> tenantIds, Collection<UUID> unitIds, Collection<UUID> propertyIds) {
        return new Names(
                tenants.findAllById(tenantIds).stream().collect(Collectors.toMap(Tenant::getId, Function.identity())),
                units.findAllById(unitIds).stream().collect(Collectors.toMap(Unit::getId, Function.identity())),
                properties.findAllById(propertyIds).stream().collect(Collectors.toMap(Property::getId, Function.identity())));
    }
}
