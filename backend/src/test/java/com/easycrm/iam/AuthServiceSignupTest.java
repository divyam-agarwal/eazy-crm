package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.iam.web.dto.AuthResponse;
import com.easycrm.iam.web.dto.SignupRequest;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.security.JwtService;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuthServiceSignupTest extends IntegrationTest {
    @Autowired
    AuthService auth;

    @Autowired
    TenantRepository tenants;

    @Autowired
    UserRepository users;

    @Autowired
    JwtService jwt;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private SignupRequest req(String slug, String email) {
        return new SignupRequest(slug, "Acme Traders", "27", null, email, null, "hunter2pass");
    }

    @Test
    void createsTenantAndOwnerAtomicallyAndReturnsUsableToken() {
        AuthResponse res = auth.signup(req("acme", "owner@acme.test"));

        assertNotNull(res.accessToken());
        assertNotNull(res.refreshToken());
        assertEquals("OWNER", res.role());

        Tenant t = tenants.findBySlug("acme").orElseThrow();
        assertEquals(res.tenantId(), t.getId());

        // The access token resolves to the new tenant, and the owner exists within it.
        TenantContext.TenantPrincipal p = jwt.parse(res.accessToken());
        assertEquals(t.getId(), p.tenantId());
        TenantContext.set(p);
        assertTrue(users.findByEmail("owner@acme.test").isPresent());
    }

    @Test
    void duplicateSlugIsConflict() {
        auth.signup(req("dupe", "a@dupe.test"));
        assertThrows(ConflictException.class, () -> auth.signup(req("dupe", "b@dupe.test")));
    }

    @Test
    void rejectsAnInvalidSellerStateCode() {
        // "39" passes @Pattern("\\d{2}") and is not a GST state code. It silently decides
        // CGST+SGST vs IGST on every quotation this tenant ever issues (MF1).
        SignupRequest req = new SignupRequest(
                "bad-state-" + UUID.randomUUID(),
                "Acme Traders",
                "39",
                null,
                "bad-state-" + UUID.randomUUID() + "@example.com",
                null,
                "hunter2pass");

        ValidationException ex = assertThrows(ValidationException.class, () -> auth.signup(req));
        assertTrue(ex.getFields().containsKey("stateCode"));
    }

    @Test
    void rejectsAMalformedSellerGstin() {
        SignupRequest req = new SignupRequest(
                "bad-gstin-" + UUID.randomUUID(),
                "Acme Traders",
                "27",
                "27AAPFU0939F1ZZ",
                "bad-gstin-" + UUID.randomUUID() + "@example.com",
                null,
                "hunter2pass");

        ValidationException ex = assertThrows(ValidationException.class, () -> auth.signup(req));
        assertTrue(ex.getFields().containsKey("gstin"));
    }

    @Test
    void rejectsASellerGstinThatDisagreesWithTheStateCode() {
        // 27… is Maharashtra; the seller claims 29 (Karnataka). One of the two is wrong and the
        // system must not pick silently — this is the same rule CustomerService already applies
        // to a buyer.
        SignupRequest req = new SignupRequest(
                "mismatch-" + UUID.randomUUID(),
                "Acme Traders",
                "29",
                "27AAPFU0939F1ZV",
                "mismatch-" + UUID.randomUUID() + "@example.com",
                null,
                "hunter2pass");

        ValidationException ex = assertThrows(ValidationException.class, () -> auth.signup(req));
        assertTrue(ex.getFields().containsKey("stateCode"));
    }

    @Test
    void acceptsAValidSellerGstin() {
        SignupRequest req = new SignupRequest(
                "good-gstin-" + UUID.randomUUID(),
                "Acme Traders",
                "27",
                "27AAPFU0939F1ZV",
                "good-gstin-" + UUID.randomUUID() + "@example.com",
                null,
                "hunter2pass");

        assertNotNull(auth.signup(req).tenantId());
    }

    @Test
    void aLowercaseSellerGstinIsPersistedUppercase() {
        // MF1's fix validated the seller's GSTIN but kept persisting the raw input, not the
        // parsed (trimmed, uppercased) form. A buyer's GSTIN is normalised by CustomerService;
        // the seller's was not — this is the test that would have caught that asymmetry.
        String slug = "lower-gstin-" + UUID.randomUUID();
        SignupRequest req = new SignupRequest(
                slug,
                "Acme Traders",
                "27",
                "27aapfu0939f1zv",
                "lower-gstin-" + UUID.randomUUID() + "@example.com",
                null,
                "hunter2pass");

        auth.signup(req);

        Tenant t = tenants.findBySlug(slug).orElseThrow();
        assertEquals("27AAPFU0939F1ZV", t.getGstin());
    }

    @Test
    void aPaddedSellerGstinIsAcceptedAndStoredTrimmed() {
        // Ordinary copy-paste padding. Gstin.parse trims and accepts it, but until the parsed
        // (not raw) value is persisted, the untrimmed 17-char string overflows tenant.gstin
        // VARCHAR(15) and surfaces as a spurious 409 rather than succeeding.
        String slug = "padded-gstin-" + UUID.randomUUID();
        SignupRequest req = new SignupRequest(
                slug,
                "Acme Traders",
                "27",
                " 27AAPFU0939F1ZV ",
                "padded-gstin-" + UUID.randomUUID() + "@example.com",
                null,
                "hunter2pass");

        auth.signup(req);

        Tenant t = tenants.findBySlug(slug).orElseThrow();
        assertEquals("27AAPFU0939F1ZV", t.getGstin());
    }
}
