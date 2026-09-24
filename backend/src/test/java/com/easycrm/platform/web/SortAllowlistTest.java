package com.easycrm.platform.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.easycrm.platform.error.ValidationException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class SortAllowlistTest {

    private static final Set<String> ALLOWED = Set.of("businessName", "createdAt");

    @Test
    void acceptsAnAllowedField() {
        assertDoesNotThrow(() -> SortAllowlist.require(PageRequest.of(0, 20, Sort.by("businessName")), ALLOWED));
    }

    @Test
    void acceptsAnUnsortedPageable() {
        assertDoesNotThrow(() -> SortAllowlist.require(PageRequest.of(0, 20), ALLOWED));
    }

    @Test
    void acceptsEveryFieldOfAMultiFieldSort() {
        assertDoesNotThrow(() -> SortAllowlist.require(
                PageRequest.of(0, 20, Sort.by("businessName").and(Sort.by("createdAt"))), ALLOWED));
    }

    @Test
    void rejectsAnUnknownField() {
        ValidationException e = assertThrows(
                ValidationException.class,
                () -> SortAllowlist.require(PageRequest.of(0, 20, Sort.by("creditDays")), ALLOWED));
        assertEquals("SORT_INVALID", e.getCodes().get("sort"));
    }

    @Test
    void rejectsWhenOnlyTheSecondFieldIsUnknown() {
        // A per-order loop that returns early after the first valid order would pass this wrongly.
        assertThrows(
                ValidationException.class,
                () -> SortAllowlist.require(
                        PageRequest.of(0, 20, Sort.by("businessName").and(Sort.by("nope"))), ALLOWED));
    }

    @Test
    void namesTheOffendingFieldButNotTheAllowlist() {
        // The message helps a developer; it must not enumerate internals back to a caller.
        ValidationException e = assertThrows(
                ValidationException.class,
                () -> SortAllowlist.require(PageRequest.of(0, 20, Sort.by("secretColumn")), ALLOWED));
        assertEquals("sort", e.getFields().keySet().iterator().next());
    }

    @Test
    void withDefaultFillsInTheSortWhenThePageableIsUnsorted() {
        var withDefault = SortAllowlist.withDefault(
                PageRequest.of(1, 20), Sort.by("businessName").ascending());
        assertEquals(Sort.by("businessName").ascending(), withDefault.getSort());
        // Page number and size must survive unchanged -- only the missing sort is filled in.
        assertEquals(1, withDefault.getPageNumber());
        assertEquals(20, withDefault.getPageSize());
    }

    @Test
    void withDefaultLeavesAnExplicitSortAlone() {
        var explicit = PageRequest.of(0, 20, Sort.by("createdAt").descending());
        assertEquals(
                explicit,
                SortAllowlist.withDefault(explicit, Sort.by("businessName").ascending()));
    }
}
