package com.revesoft.tms.rent;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RentInvoiceRepository extends JpaRepository<RentInvoice, UUID> {

    List<RentInvoice> findByTenantIdOrderByBillingMonthDesc(UUID tenantId);

    List<RentInvoice> findByAgreementIdOrderByBillingMonthDesc(UUID agreementId);

    List<RentInvoice> findByStatusIn(Collection<RentInvoice.Status> statuses);

    List<RentInvoice> findByBillingMonth(LocalDate billingMonth);

    boolean existsByAgreementIdAndBillingMonthAndStatusNot(UUID agreementId, LocalDate billingMonth,
                                                          RentInvoice.Status status);
}
