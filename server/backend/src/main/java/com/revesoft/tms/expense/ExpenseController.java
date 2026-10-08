package com.revesoft.tms.expense;

import com.revesoft.tms.expense.ExpenseService.ExpenseList;
import com.revesoft.tms.expense.ExpenseService.ExpenseRequest;
import com.revesoft.tms.expense.ExpenseService.ExpenseView;
import jakarta.validation.Valid;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/expenses")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class ExpenseController {

    private final ExpenseService service;

    public ExpenseController(ExpenseService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return Expense.CATEGORIES;
    }

    @GetMapping
    public ExpenseList list(@RequestParam(required = false) UUID propertyId,
                            @RequestParam(required = false) String category,
                            @RequestParam(required = false) LocalDate from,
                            @RequestParam(required = false) LocalDate to) {
        return service.list(propertyId, category, from, to);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseView create(@Valid @RequestBody ExpenseRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public ExpenseView update(@PathVariable UUID id, @Valid @RequestBody ExpenseRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
