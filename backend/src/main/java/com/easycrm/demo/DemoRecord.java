package com.easycrm.demo;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "demo_record")
public class DemoRecord extends TenantScopedEntity {

    @Column(nullable = false)
    private String label;

    protected DemoRecord() {}

    public DemoRecord(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
