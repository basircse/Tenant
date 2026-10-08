package com.revesoft.tms.expense;

import com.revesoft.tms.audit.AuditService;
import com.revesoft.tms.common.BusinessClock;
import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.common.Money;
import com.revesoft.tms.property.PropertyRepository;
import com.revesoft.tms.security.AccessGuard;
import com.revesoft.tms.security.AuthUser;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Property expenses, used by the income-vs-expense report. */
@Service
public class ExpenseService {

    private final ExpenseRepository expenses;
    private final PropertyRepository properties;
    private final AccessGuard guard;
    private final AuditService audit;
    private final BusinessClock clock;

    public ExpenseService(ExpenseRepository expenses, PropertyRepository properties, AccessGuard guard,
                          AuditService audit, BusinessClock clock) {
        this.expenses = expenses;
        this.properties = properties;
        this.guard = guard;
        this.audit = audit;
        this.clock = clock;
    }

    public record ExpenseRequest(UUID propertyId, LocalDate expenseDate, @NotBlank String category,
                                 @NotNull @DecimalMin(value = "0.01", message = "must be greater than zero") BigDecimal amount,
                                 String description) {
    }

    public record ExpenseView(UUID id, UUID propertyId, String propertyName, LocalDate expenseDate, String category,
                              BigDecimal amount, String description) {
    }

    public record ExpenseList(BigDecimal total, List<ExpenseView> items) {
    }

    @Transactional(readOnly = true)
    public ExpenseList list(UUID propertyId, String category, LocalDate from, LocalDate to) {
        guard.requireStaff();
        Set<UUID> scope = guard.propertyScope();
        List<ExpenseView> items = expenses.findAllByOrderByExpenseDateDescCreatedAtDesc().stream()
                .filter(e -> e.getPropertyId() == null ? scope == null : AccessGuard.inScope(scope, e.getPropertyId()))
                .filter(e -> propertyId == null || propertyId.equals(e.getPropertyId()))
                .filter(e -> category == null || category.equals(e.getCategory()))
                .filter(e -> from == null || !e.getExpenseDate().isBefore(from))
                .filter(e -> to == null || !e.getExpenseDate().isAfter(to))
                .map(this::view)
                .toList();
        return new ExpenseList(Money.of(items.stream().map(ExpenseView::amount).reduce(BigDecimal.ZERO, BigDecimal::add)),
                items);
    }

    @Transactional
    public ExpenseView create(ExpenseRequest r) {
        guard.requireStaff();
        Expense e = new Expense();
        apply(e, r);
        expenses.save(e);
        audit.log("Created Expense", "Expense", e.getId().toString(), e.getCategory() + " " + e.getAmount());
        return view(e);
    }

    @Transactional
    public ExpenseView update(UUID id, ExpenseRequest r) {
        Expense e = load(id);
        apply(e, r);
        audit.log("Updated Expense", "Expense", e.getId().toString(), e.getCategory() + " " + e.getAmount());
        return view(e);
    }

    @Transactional
    public void delete(UUID id) {
        Expense e = load(id);
        expenses.delete(e);
        audit.log("Deleted Expense", "Expense", e.getId().toString(), e.getCategory() + " " + e.getAmount());
    }

    private Expense load(UUID id) {
        guard.requireStaff();
        Expense e = AccessGuard.owned(expenses.findById(id), "Expense");
        checkScope(e.getPropertyId());
        return e;
    }

    /** General (all-property) expenses are admin-only. */
    private void checkScope(UUID propertyId) {
        AuthUser me = guard.user();
        if (propertyId == null) {
            if (!me.isAdmin()) {
                throw BusinessException.forbidden("Only administrators manage general expenses");
            }
        } else {
            guard.checkProperty(propertyId);
        }
    }

    private void apply(Expense e, ExpenseRequest r) {
        if (r.propertyId() != null) {
            AccessGuard.owned(properties.findById(r.propertyId()), "Property");
        }
        checkScope(r.propertyId());
        e.setPropertyId(r.propertyId());
        e.setExpenseDate(r.expenseDate() == null ? clock.today() : r.expenseDate());
        e.setCategory(r.category().trim());
        e.setAmount(Money.of(r.amount()));
        e.setDescription(r.description());
    }

    private ExpenseView view(Expense e) {
        String property = e.getPropertyId() == null ? null
                : properties.findById(e.getPropertyId()).map(p -> p.getName()).orElse(null);
        return new ExpenseView(e.getId(), e.getPropertyId(), property, e.getExpenseDate(), e.getCategory(),
                e.getAmount(), e.getDescription());
    }
}
