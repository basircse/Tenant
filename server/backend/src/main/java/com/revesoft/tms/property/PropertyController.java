package com.revesoft.tms.property;

import com.revesoft.tms.property.PropertyService.PropertyDetail;
import com.revesoft.tms.property.PropertyService.PropertyRequest;
import com.revesoft.tms.property.PropertyService.PropertyView;
import com.revesoft.tms.property.PropertyService.UnitRequest;
import com.revesoft.tms.property.PropertyService.UnitView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class PropertyController {

    private final PropertyService service;

    public PropertyController(PropertyService service) {
        this.service = service;
    }

    public record PropertyStatusRequest(@NotNull Property.Status status) {
    }

    @GetMapping("/api/properties")
    public List<PropertyView> list(@RequestParam(required = false) String q,
                                   @RequestParam(required = false) Property.Type type,
                                   @RequestParam(required = false) Property.Status status) {
        return service.list(q, type, status);
    }

    @GetMapping("/api/properties/{id}")
    public PropertyDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/api/properties")
    @ResponseStatus(HttpStatus.CREATED)
    public PropertyDetail create(@Valid @RequestBody PropertyRequest request) {
        return service.create(request);
    }

    @PutMapping("/api/properties/{id}")
    public PropertyDetail update(@PathVariable UUID id, @Valid @RequestBody PropertyRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/api/properties/{id}/status")
    public PropertyDetail setStatus(@PathVariable UUID id, @Valid @RequestBody PropertyStatusRequest request) {
        return service.setStatus(id, request.status());
    }

    @GetMapping("/api/properties/{id}/units")
    public List<UnitView> units(@PathVariable UUID id) {
        return service.listUnits(id);
    }

    @PostMapping("/api/properties/{id}/units")
    @ResponseStatus(HttpStatus.CREATED)
    public UnitView createUnit(@PathVariable UUID id, @Valid @RequestBody UnitRequest request) {
        return service.createUnit(id, request);
    }

    @GetMapping("/api/units")
    public List<UnitView> allUnits() {
        return service.listAllUnits();
    }

    @GetMapping("/api/units/{unitId}")
    public UnitView getUnit(@PathVariable UUID unitId) {
        return service.getUnit(unitId);
    }

    @PutMapping("/api/units/{unitId}")
    public UnitView updateUnit(@PathVariable UUID unitId, @Valid @RequestBody UnitRequest request) {
        return service.updateUnit(unitId, request);
    }

    @DeleteMapping("/api/units/{unitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUnit(@PathVariable UUID unitId) {
        service.deleteUnit(unitId);
    }
}
