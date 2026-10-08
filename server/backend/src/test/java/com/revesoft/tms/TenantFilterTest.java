package com.revesoft.tms;

import static org.assertj.core.api.Assertions.assertThat;

import com.revesoft.tms.property.PropertyRepository;
import com.revesoft.tms.security.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Verifies Hibernate's @TenantId filter itself, below the service-layer checks. */
class TenantFilterTest extends ApiTestBase {

    @Autowired
    PropertyRepository properties;

    @Test
    void repositoryCannotLoadAnotherOrganisationsRows() throws Exception {
        Org a = newOrg();
        Org b = newOrg();
        UUID propertyA = UUID.fromString(createProperty(a.token(), "Only A"));

        assertThat(tx.inOrg(a.id(), () -> properties.findById(propertyA))).isPresent();
        assertThat(tx.inOrg(b.id(), () -> properties.findById(propertyA))).isEmpty();
        assertThat(tx.inOrg(b.id(), () -> properties.findAll())).noneMatch(p -> p.getId().equals(propertyA));
        // Unauthenticated code sees nothing at all.
        assertThat(tx.inOrg(TenantContext.NONE, () -> properties.count())).isZero();
        // ROOT (login / jobs) sees everything.
        assertThat(tx.asRoot(() -> properties.findById(propertyA))).isPresent();
    }
}
