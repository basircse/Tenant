package com.revesoft.tms.payment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByInvoiceIdOrderByPaymentDateAscCreatedAtAsc(UUID invoiceId);

    List<Payment> findByTenantIdOrderByPaymentDateDescCreatedAtDesc(UUID tenantId);

    List<Payment> findAllByOrderByPaymentDateDescCreatedAtDesc();
}
