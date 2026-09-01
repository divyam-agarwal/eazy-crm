package com.easycrm.sales;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "document_counter",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_doc_counter_tenant_type_fy",
                        columnNames = {"tenant_id", "doc_type", "fy"}))
public class DocumentCounter extends TenantScopedEntity {

    @Column(name = "doc_type", nullable = false, length = 16)
    private String docType;

    @Column(nullable = false, length = 7)
    private String fy;

    @Column(name = "next_val", nullable = false)
    private long nextVal;

    protected DocumentCounter() {}

    public DocumentCounter(String docType, String fy) {
        this.docType = docType;
        this.fy = fy;
        this.nextVal = 1;
    }

    public long getNextVal() {
        return nextVal;
    }

    public void increment() {
        this.nextVal++;
    }

    public String getDocType() {
        return docType;
    }

    public String getFy() {
        return fy;
    }
}
