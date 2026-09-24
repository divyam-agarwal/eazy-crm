package com.easycrm.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.easycrm.support.IntegrationTest;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V36 is load-bearing for F1a's search: without the GIN trigram indexes every `q` query seq-scans,
 * and without the btree indexes every list sorts a whole tenant in memory. An index that silently
 * fails to be created is invisible from the application side, so assert it directly.
 */
@SpringBootTest
class MasterDataSearchIndexTest extends IntegrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void pgTrgmExtensionIsInstalled() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class);
        assertEquals(1, count, "pg_trgm must be installed by V36");
    }

    @Test
    void searchAndSortIndexesExist() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> expected = List.of(
                "idx_customer_business_name",
                "idx_customer_business_name_trgm",
                "idx_customer_gstin_trgm",
                "idx_product_name",
                "idx_product_name_trgm",
                "idx_product_sku_trgm",
                "idx_price_list_name",
                "idx_price_list_name_trgm");
        List<String> actual =
                jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = current_schema()", String.class);
        for (String name : expected) {
            assertTrue(actual.contains(name), "missing index: " + name);
        }
    }

    /**
     * A trigram index built on the raw column (rather than lower(col)) is still a GIN index, still
     * named correctly, and still passes {@link #searchAndSortIndexesExist()} -- but it can never be
     * used by the LOWER(col) LIKE LOWER('%needle%') predicate the Specifications build, so it would
     * defeat the whole point of this migration while every other assertion stayed green. Checking
     * indexdef for both `lower(` and `gin_trgm_ops` on all five trigram indexes catches that: it
     * subsumes the GIN-type check (gin_trgm_ops only attaches under a GIN index definition) and the
     * expression-shape check in one query per index.
     */
    @Test
    void trigramIndexesUseLowerExpressionAndGin() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> trigramIndexes = List.of(
                "idx_customer_business_name_trgm",
                "idx_customer_gstin_trgm",
                "idx_product_name_trgm",
                "idx_product_sku_trgm",
                "idx_price_list_name_trgm");
        for (String name : trigramIndexes) {
            String indexDef = jdbc.queryForObject(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?",
                    String.class,
                    name);
            assertTrue(indexDef.contains("lower("), name + " must index lower(...), not the raw column: " + indexDef);
            assertTrue(indexDef.contains("gin_trgm_ops"), name + " must use gin_trgm_ops: " + indexDef);
        }
    }
}
