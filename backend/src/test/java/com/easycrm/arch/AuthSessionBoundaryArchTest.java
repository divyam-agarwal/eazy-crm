package com.easycrm.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.easycrm.iam.AuthService;
import com.easycrm.iam.RefreshTokenService;
import com.easycrm.iam.web.AuthController;
import com.easycrm.iam.web.PublicInvitationController;
import com.easycrm.iam.web.RefreshCookie;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Spec 2026-09-14-f0 §3.8. The refresh cookie authenticates exactly one operation — rotation, via
 * POST /auth/refresh — and only two controllers may handle the cookie at all.
 *
 * <p>Each assertion is an EQUALITY against the expected set, not a subset: it fails if a new caller
 * appears AND if the expected caller disappears, so it cannot pass by matching nothing.
 */
class AuthSessionBoundaryArchTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.easycrm");

    @Test
    void onlyAuthServiceRotatesRefreshTokens() {
        Set<String> callers = new TreeSet<>();
        MAIN.forEach(c -> {
            collectRotateCallers(c.getMethodCallsFromSelf(), callers);
            collectRotateCallers(c.getMethodReferencesFromSelf(), callers);
        });
        callers.remove(RefreshTokenService.class.getName()); // rotate(raw) delegating to rotate(raw, now)

        assertThat(callers).containsExactly(AuthService.class.getName());
    }

    // ArchUnit tracks a `refreshTokens::rotate` method reference separately from a
    // `refreshTokens.rotate(...)` call -- both reach RefreshTokenService.rotate and must be
    // checked, or a future bound reference (e.g. `.map(refreshTokens::rotate)`) would bypass this
    // guard silently. Same reasoning VisibilityScopingArchTest applies to guarded-repository reads.
    private static void collectRotateCallers(Set<? extends JavaAccess<?>> accesses, Set<String> callers) {
        accesses.stream()
                .filter(access -> access.getTargetOwner().isEquivalentTo(RefreshTokenService.class))
                .filter(access -> access.getName().equals("rotate"))
                .forEach(access -> callers.add(access.getOriginOwner().getName()));
    }

    @Test
    void onlyTheTwoAuthControllersTouchTheRefreshCookie() {
        Set<String> users = new TreeSet<>();
        MAIN.forEach(c -> c.getDirectDependenciesFromSelf().stream()
                .filter(d -> d.getTargetClass().isEquivalentTo(RefreshCookie.class))
                .forEach(d -> users.add(d.getOriginClass().getName())));
        users.remove(RefreshCookie.class.getName());

        assertThat(users)
                .containsExactlyInAnyOrder(AuthController.class.getName(), PublicInvitationController.class.getName());
    }
}
