package com.easycrm.crm;

import com.easycrm.iam.AssignedWorkload;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Active customers assigned to a member. An inactive customer needs no owner. */
@Component
public class CustomerWorkload implements AssignedWorkload {

    private final CustomerRepository customers;

    public CustomerWorkload(CustomerRepository customers) {
        this.customers = customers;
    }

    @Override
    public String label() {
        return "customers";
    }

    @Override
    public long countOpenFor(UUID userId) {
        return customers.countByAssignedToAndActiveTrue(userId);
    }
}
