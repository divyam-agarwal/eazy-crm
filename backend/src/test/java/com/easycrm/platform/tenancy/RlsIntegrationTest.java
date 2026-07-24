package com.easycrm.platform.tenancy;

import com.easycrm.demo.DemoRecord;
import com.easycrm.demo.DemoRecordRepository;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RlsIntegrationTest extends IntegrationTest {
    @Autowired DemoRecordRepository records;
    @Autowired DataSource dataSource; // app (non-owner) datasource

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void rawQueryWithoutTenantContextReturnsZeroRows() throws Exception {
        UUID a = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(a, UUID.randomUUID(), "OWNER"));
        records.saveAndFlush(new DemoRecord("a-1"));
        TenantContext.clear();

        // Fresh connection from the app (non-owner) pool, no app.current_tenant set.
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM demo_record")) {
            rs.next();
            assertEquals(0, rs.getInt(1),
                "RLS blocks the non-owner app role when no tenant is set");
        }
    }
}
