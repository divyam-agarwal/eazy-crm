package com.easycrm.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Layer 2 of split-readiness: reading another domain package's repository is reading another
 * SERVICE's tables once those packages are extracted. Rule A (ModuleDirectionArchTest) cannot see
 * this, and neither can Modulith's verify() — a cross-service read is not a cycle, so it surfaces
 * only as a "non-exposed type" finding, which declaring platform OPEN silences.
 *
 * <p><b>The allowlist is a register with an exit plan, not an exemption.</b> Every entry names how
 * it goes away — a synchronous internal call, an event, a freeze, or "intra-service, no action".
 * Adding an entry is a deliberate decision that costs review, the same way
 * TenantScopingArchTest.GLOBAL_TABLES and VisibilityScopingArchTest.ALLOWED_METHODS do. Resolving
 * them is roadmap item 3b (cross-service data access), which is owed before SP8.
 *
 * <p><b>This rule is immune to the limitation {@link VisibilityScopingArchTest} documents.</b> That
 * sibling inspects method-call and method-reference target owners, and an inherited
 * {@code CrudRepository} method (e.g. {@code repo::findById}) resolves its owner to the Spring Data
 * supertype rather than to the local repository interface, so an owner-name check misses it. Rule B
 * here does not look at method targets at all — it walks {@code getDirectDependenciesFromSelf()},
 * which reports the type-level edge created by merely declaring a field of the concrete repository
 * type. That edge exists regardless of which method (inherited or declared) is ever called on the
 * field, so inherited-method resolution is irrelevant to it.
 *
 * <p>See spec 2026-09-13-wave-1.6-module-boundaries-design.md §3.4 and §5.2.
 */
class CrossDomainRepositoryArchTest {

    /** The packages that become services. platform is a shared library and is Rule A's business. */
    private static final Set<String> DOMAIN_PACKAGES = Set.of("crm", "sales", "catalog", "tenant", "iam");

    /**
     * "<reader FQCN> -> <repository FQCN>", one per permitted cross-domain read.
     *
     * <p>Exits, per spec §3.4:
     * <ul>
     *   <li>ShareLinkService -> crm.ContactRepository — SYNCHRONOUS CALL. Already designed as
     *       {@code GET /internal/customers/{id}/contacts/primary} (service-scope §1.2). The wa.me
     *       recipient is a routing address and needs freshness, which is why S2's freeze was
     *       declined in the buyer-snapshot slice.</li>
     *   <li>PriceResolver -> catalog.ProductRepository / PriceListItemRepository — SYNCHRONOUS
     *       CALL, fail-fast 503 (service-scope §1.3: "Price list item, product -> master-data.
     *       Fail fast, 503").</li>
     *   <li>ShareLinkService / QuotationService / QuotationPdfService -> tenant.TenantRepository —
     *       FREEZE, not a call. All three read the seller's own profile; the letterhead belongs in
     *       document-svc's immutable render payload (service-scope §1.4, F13). <b>This is H7: today
     *       the read is live, and the tax presentation depends on it.</b></li>
     *   <li>AuthService / MemberService / InvitationService -> tenant.TenantRepository — NO ACTION
     *       NEEDED. identity-svc owns both iam and tenant (service-scope §1.1), so this edge does
     *       not cross a service line. It is listed to record that it was considered, not deferred.</li>
     * </ul>
     */
    private static final Set<String> ALLOWED = Set.of(
            "com.easycrm.sales.ShareLinkService -> com.easycrm.crm.ContactRepository",
            "com.easycrm.sales.PriceResolver -> com.easycrm.catalog.ProductRepository",
            "com.easycrm.sales.PriceResolver -> com.easycrm.catalog.PriceListItemRepository",
            "com.easycrm.sales.ShareLinkService -> com.easycrm.tenant.TenantRepository",
            "com.easycrm.sales.QuotationService -> com.easycrm.tenant.TenantRepository",
            "com.easycrm.sales.pdf.QuotationPdfService -> com.easycrm.tenant.TenantRepository",
            "com.easycrm.iam.AuthService -> com.easycrm.tenant.TenantRepository",
            "com.easycrm.iam.MemberService -> com.easycrm.tenant.TenantRepository",
            "com.easycrm.iam.InvitationService -> com.easycrm.tenant.TenantRepository");

    private static JavaClasses importedClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.easycrm");
    }

    @Test
    void noDomainPackageReadsAnotherDomainPackagesRepository() {
        ArchRule rule = noClasses()
                .should(dependOnAnUnallowlistedForeignRepository())
                .because("a repository read across a domain boundary is a read of another service's "
                        + "tables once those packages are extracted (Layer 2, roadmap item 3b)");

        rule.check(importedClasses());
    }

    @Test
    void theImportIsNotVacuous() {
        assertThat(importedClasses().size()).isGreaterThan(150);
    }

    /**
     * Every allowlist entry must still describe a real dependency. A stale entry is worse than a
     * missing one: it reads as a known blocker that no longer exists, and roadmap item 3b would
     * design a resolution for nothing.
     */
    @Test
    void everyAllowlistEntryIsStillReal() {
        Set<String> actual = actualCrossDomainReads();
        assertThat(actual)
                .as("allowlisted reads that no longer exist -- delete them from ALLOWED")
                .containsAll(ALLOWED);
    }

    private static Set<String> actualCrossDomainReads() {
        java.util.HashSet<String> found = new java.util.HashSet<>();
        for (JavaClass item : importedClasses()) {
            for (Dependency d : item.getDirectDependenciesFromSelf()) {
                String edge = describe(item, d.getTargetClass());
                if (edge != null) found.add(edge);
            }
        }
        return found;
    }

    /** Non-null only when this is a cross-domain repository dependency. */
    /**
     * Non-null only when this is a cross-domain repository dependency.
     *
     * <p><b>Known residual gap, not fixed here:</b> a field typed with the Spring Data SUPERTYPE
     * (e.g. {@code JpaRepository<Customer, UUID>}) has a simple name ending in {@code Repository},
     * but {@code domainOf("org.springframework.data.jpa.repository")} returns null at its very
     * first guard — that package does not start with {@code "com.easycrm."} — so it never even
     * reaches the {@link #DOMAIN_PACKAGES} membership check, and this method returns null and the
     * read slips through silently. This does not occur today — every repository is injected as its
     * concrete interface, verified by grep — but a future field declared against the supertype
     * would bypass this rule with no signal. Left undocumented-but-unfixed is worse than documented
     * and accepted.
     */
    private static String describe(JavaClass from, JavaClass target) {
        if (!target.getSimpleName().endsWith("Repository")) return null;
        String fromDomain = domainOf(from.getPackageName());
        String toDomain = domainOf(target.getPackageName());
        if (fromDomain == null || toDomain == null) return null;
        if (fromDomain.equals(toDomain)) return null;
        return from.getFullName() + " -> " + target.getFullName();
    }

    /** "com.easycrm.sales.pdf" -> "sales"; null for platform, demo and anything outside. */
    private static String domainOf(String packageName) {
        if (!packageName.startsWith("com.easycrm.")) return null;
        String rest = packageName.substring("com.easycrm.".length());
        int dot = rest.indexOf('.');
        String head = dot < 0 ? rest : rest.substring(0, dot);
        return DOMAIN_PACKAGES.contains(head) ? head : null;
    }

    private static ArchCondition<JavaClass> dependOnAnUnallowlistedForeignRepository() {
        return new ArchCondition<>("read another domain package's repository outside the allowlist") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Dependency d : item.getDirectDependenciesFromSelf()) {
                    String edge = describe(item, d.getTargetClass());
                    if (edge == null) continue;
                    if (ALLOWED.contains(edge)) continue;
                    events.add(SimpleConditionEvent.satisfied(
                            item,
                            edge + " -- add an entry to ALLOWED with its exit, or route it "
                                    + "through a port (see iam.AssignedWorkload)"));
                }
            }
        };
    }
}
