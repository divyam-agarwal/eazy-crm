package com.easycrm.arch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every hand-thrown master-data validation or conflict error must carry a reason code, so a client
 * can translate it instead of showing English prose (docs/api/error-codes.md rule 3).
 *
 * <p>This is source inspection, not ArchUnit, and deliberately so: ArchUnit sees types and call
 * edges, never constructor <em>arguments</em>, so it cannot distinguish
 * {@code new ValidationException(field, message)} from
 * {@code new ValidationException(field, message, CODE)}. Do not "upgrade" this to an ArchUnit rule;
 * it would silently stop checking anything.
 *
 * <p><b>Two call shapes are recognised.</b> The direct shape passes the code as a literal, e.g.
 * {@code new ValidationException("field", "message", "CODE")} or
 * {@code new ConflictException(msg, fields, Map.of("field", "CODE"))} -- detected by requiring the
 * <em>last</em> quoted string literal anywhere in the constructor arguments to look like
 * {@code SCREAMING_SNAKE}. Checking only the last literal (not "any literal in the call", which is
 * what an earlier draft of this guard did) matters: an all-caps, underscore-bearing *message*
 * quoted earlier in the argument list must not be mistaken for a code. The accumulator shape,
 * used by {@code ProductService.validate}, builds two {@code Map<String,String>} locals across
 * several {@code if} blocks and throws {@code new ValidationException(errors, codes)} with no
 * literal in the call at all -- detected by taking the final bare-identifier argument and
 * confirming it is populated somewhere earlier in the file via {@code codes.put(key, "CODE")}.
 * Verified empirically (2026-09-24) that no other {@code SCREAMING_SNAKE}-shaped string literal of
 * 4+ characters appears anywhere in the guarded files, so scanning per-file rather than per-method
 * for the accumulator's {@code .put} calls does not currently admit noise; if a future guarded file
 * needs an unrelated all-caps string constant, this will need tightening to a method-scoped search.
 *
 * <p><b>Two call styles this guard cannot see through</b> -- both fail LOUD (a legitimate call is
 * flagged as a false-positive offender, forcing a human to look), never silently: (1) a code
 * referenced as a constant rather than a literal, e.g.
 * {@code new ValidationException("field", "message", ErrorCodes.SOME_CODE)} -- {@code ErrorCodes.
 * SOME_CODE} is not a quoted string, so the direct shape sees no code-shaped final literal and the
 * accumulator shape's bare-identifier check also fails (the last argument contains a dot); (2) a
 * "mixed" call combining a literal fields map with an accumulator-style codes variable, e.g.
 * {@code new ValidationException(Map.of("hsnCode", "bad"), codes)} -- the presence of any quoted
 * literal routes this into the direct-shape branch, which then fails because the last quoted
 * literal is the message, not a code, so the (real) {@code codes} variable is never inspected. Both
 * are style constraints on how these two exceptions may be constructed in the guarded files, not
 * holes in the guard.
 */
class MasterDataErrorCodesTest {

    /**
     * The hand-thrown call sites this guard protects: the four master-data services from Tasks 5/6,
     * {@code AssignableUsers} (Task 5's {@code ASSIGNEE_INVALID}, added outside {@code crm}/
     * {@code catalog}), and {@code SortAllowlist} ({@code SORT_INVALID}, predating both tasks --
     * added in commit 70cda97 and simply missed when this guard was first written).
     *
     * <p>This is an explicit allowlist, not a package-wide walk over every {@code ValidationException}
     * / {@code ConflictException} call site in {@code com.easycrm}, and that is deliberate rather than
     * an oversight: {@code sales/*} (Quotation, Order, Enquiry, Activity, FollowUp, EnquiryService,
     * QuotationService, ShareLinkService, ...) throws both exceptions in many places with no reason
     * code at all, by design -- coding those is F2 work, not F1a's. A package-wide walk would be a
     * strictly stronger guard, but it would go permanently red on that pre-existing, deliberately
     * uncoded surface the moment it was written, which would either block this task or force
     * grandfathering every one of those call sites by name. Do not "improve" this into a walk without
     * first coding (or explicitly grandfathering) {@code sales/*}.
     */
    private static final List<Path> GUARDED = List.of(
            Path.of("src/main/java/com/easycrm/crm/CustomerService.java"),
            Path.of("src/main/java/com/easycrm/catalog/ProductService.java"),
            Path.of("src/main/java/com/easycrm/catalog/PriceListService.java"),
            Path.of("src/main/java/com/easycrm/catalog/PriceListItemService.java"),
            Path.of("src/main/java/com/easycrm/iam/AssignableUsers.java"),
            Path.of("src/main/java/com/easycrm/platform/web/SortAllowlist.java"));

    /** `new ValidationException(` or `new ConflictException(` through to the closing paren. */
    private static final Pattern THROW_SITE =
            Pattern.compile("new (ValidationException|ConflictException)\\s*\\(([^;]*?)\\)\\s*;", Pattern.DOTALL);

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    /**
     * At least one underscore-separated group, not just "starts uppercase" -- a bare two-letter
     * literal like {@code "OK"} must not read as a code (see everyCodeThrownIsRegistered's table-row
     * anchoring for the matching half of this tightening).
     */
    private static final Pattern SCREAMING_SNAKE = Pattern.compile("[A-Z][A-Z0-9]*(_[A-Z0-9]+)+");

    /** A registered code: the first (backtick-quoted) column of a Domain codes table row. */
    private static final Pattern REGISTERED_CODE_ROW = Pattern.compile("(?m)^\\|\\s*`([A-Z][A-Z0-9_]+)`\\s*\\|");

    @Test
    void everyGuardedFileExists() {
        // Non-vacuity: a renamed or moved service must fail loudly, not quietly stop being checked.
        for (Path p : GUARDED) {
            assertTrue(Files.exists(p), "guarded file is missing -- update GUARDED: " + p);
        }
    }

    @Test
    void theGuardActuallyFindsThrowSites() {
        // Non-vacuity: if the regex stops matching, every other assertion here passes for free.
        int found = 0;
        for (Path p : GUARDED) {
            found += countMatches(read(p));
        }
        // Measured 2026-09-24 across the six guarded files after Tasks 5/6 and fix round 1: 13 throw
        // sites (CustomerService 3, ProductService 2, PriceListService 2, PriceListItemService 4,
        // AssignableUsers 1, SortAllowlist 1). Pinning the floor to the measured count means deleting
        // even one guarded throw site fails this test, not just a mass deletion.
        assertTrue(found >= 13, "expected to find the known throw sites, found " + found);
    }

    @Test
    void noFieldedErrorIsThrownWithoutACode() {
        List<String> offenders = new ArrayList<>();
        for (Path p : GUARDED) {
            String src = read(p);
            Matcher m = THROW_SITE.matcher(src);
            while (m.find()) {
                List<String> codes = codesFor(m.group(2), src.substring(0, m.start()));
                if (codes.isEmpty()) {
                    offenders.add(p.getFileName() + ": new " + m.group(1) + "(" + oneLine(m.group(2)) + ")");
                }
            }
        }
        assertTrue(
                offenders.isEmpty(),
                "master-data errors thrown without a reason code:\n" + String.join("\n", offenders));
    }

    @Test
    void everyCodeThrownIsRegistered() throws IOException {
        Set<String> registered = registeredCodes();
        List<String> unregistered = new ArrayList<>();
        for (Path p : GUARDED) {
            String src = read(p);
            Matcher m = THROW_SITE.matcher(src);
            while (m.find()) {
                for (String c : codesFor(m.group(2), src.substring(0, m.start()))) {
                    if (!registered.contains(c)) unregistered.add(c);
                }
            }
        }
        assertTrue(unregistered.isEmpty(), "codes thrown but absent from docs/api/error-codes.md: " + unregistered);
    }

    @Test
    void theRegistryCheckCanFail() throws IOException {
        // Non-vacuity for the assertion above: prove the registry is actually being read and that a
        // bogus code would not be found in it.
        assertFalse(registeredCodes().contains("DEFINITELY_NOT_A_REAL_CODE"));
    }

    // -- codesFor non-vacuity: R20. Every other rule here is guarded (file existence, the throw-site
    // floor, the registry-check control) except codesFor itself, whose emptiness the three mutations
    // in the task report proved by hand, once, unrepeatably. These four cases make that permanent and
    // machine-checked: both shapes, in both the "no code" and "has a code" direction.

    @Test
    void codesFor_directShapeNoCode_isEmpty() {
        assertTrue(codesFor("\"f\", \"m\"", "").isEmpty());
    }

    @Test
    void codesFor_directShapeWithCode_containsIt() {
        assertTrue(codesFor("\"f\", \"m\", \"A_CODE\"", "").contains("A_CODE"));
    }

    @Test
    void codesFor_accumulatorShapeNoCode_isEmpty() {
        assertTrue(codesFor("errors", "errors.put(\"f\", \"a message\");").isEmpty());
    }

    @Test
    void codesFor_accumulatorShapeWithCode_containsIt() {
        assertTrue(codesFor("errors", "errors.put(\"f\", \"A_CODE\");").contains("A_CODE"));
    }

    /**
     * The reason code(s), if any, that this throw site carries -- empty means "no code", i.e. an
     * offender. See the class javadoc for the two shapes this recognises and the two it cannot see
     * through.
     */
    private static List<String> codesFor(String args, String textBeforeThrow) {
        List<String> quoted = new ArrayList<>();
        Matcher q = QUOTED.matcher(args);
        while (q.find()) quoted.add(q.group(1));

        if (!quoted.isEmpty()) {
            // Direct shape: the code, if present, is the LAST quoted literal in the call -- not
            // just any quoted literal, so an all-caps *message* quoted earlier in the argument
            // list is never mistaken for a code.
            String last = quoted.get(quoted.size() - 1);
            if (!SCREAMING_SNAKE.matcher(last).matches()) {
                return List.of();
            }
            List<String> codes = new ArrayList<>();
            for (String s : quoted) {
                if (SCREAMING_SNAKE.matcher(s).matches()) codes.add(s);
            }
            return codes;
        }

        // Accumulator shape: no literal at all in the call, e.g. `new ValidationException(errors,
        // codes)`. The last bare-identifier argument is the codes map; confirm it is actually
        // populated with a SCREAMING_SNAKE code earlier in the same file.
        String[] parts = args.split(",");
        if (parts.length == 0) return List.of();
        String lastArg = parts[parts.length - 1].trim();
        if (!lastArg.matches("[A-Za-z_][A-Za-z0-9_]*")) return List.of();

        Pattern populate = Pattern.compile(
                Pattern.quote(lastArg) + "\\.put\\(\\s*\"[^\"]*\"\\s*,\\s*\"([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\"");
        Matcher put = populate.matcher(textBeforeThrow);
        List<String> codes = new ArrayList<>();
        while (put.find()) codes.add(put.group(1));
        return codes;
    }

    /** Codes registered in the Domain codes table -- anchored to the table's code column. */
    private static Set<String> registeredCodes() throws IOException {
        String registry = Files.readString(Path.of("../docs/api/error-codes.md"));
        Set<String> codes = new HashSet<>();
        Matcher m = REGISTERED_CODE_ROW.matcher(registry);
        while (m.find()) codes.add(m.group(1));
        return codes;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + p, e);
        }
    }

    private static int countMatches(String src) {
        Matcher m = THROW_SITE.matcher(src);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
