package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * A migration is append-only once released (docs/requirements/requirements.md, Persistence): the
 * owner's {@code flyway_schema_history} records each applied script's checksum, and Flyway refuses
 * to start a ledger whose script no longer matches it.
 *
 * <p>No {@code *IT} can see an in-place edit. Each builds its database from the scripts on today's
 * classpath — {@link LedgerUpgradeIT} included, which copies the <em>current</em> bytes — so an edited
 * {@code V1_1} migrates every fresh database cleanly while the owner's, holding the old checksum,
 * would not start. This pins each released script to the digest it had when it shipped.
 *
 * <p>A SHA-256 of the bytes rather than Flyway's own checksum: Flyway computes that through an
 * internal class, and its CRC32 forgives line-ending and BOM changes. The bytes are stable on every
 * machine because {@code .gitattributes} checks every text file out with {@code eol=lf}, and a
 * released script has no business changing even in ways Flyway would forgive.
 *
 * <p>Scripts are discovered from the classpath, so a new {@code V*.sql} fails this test until its
 * digest is recorded below — the moment to decide that it is released. Never change a digest once it
 * is merged to {@code dev}; write the next migration instead.
 */
class ReleasedMigrationsTest {

    private static final String MIGRATIONS = "db/migration/";

    /** The major version each context's directory owns (ADR-024), so its scripts cannot collide. */
    private static final Map<String, Integer> MAJOR_BY_DIRECTORY =
            Map.of("accounting", 1, "budgeting", 2, "planning", 3);

    private static final Pattern VERSIONED_SCRIPT = Pattern.compile("^V(\\d+(?:_\\d+)*)__.+\\.sql$");

    /**
     * Every released script, by its path under {@code db/migration/} and the SHA-256 it shipped with,
     * in the order it was released: append a new one at the end. {@code V1_1}, {@code V2_1} and {@code
     * V3_1} entered git together, and {@code V1_2} followed, which is the history {@link LedgerUpgradeIT}
     * rebuilds.
     */
    private static final List<Released> RELEASED = List.of(
            new Released(
                    "accounting/V1_1__accounting_schema.sql",
                    "511c03cf68f8880a2c76b278bd281bc81a22e361c436f1c1c9374c5ae2d4f2bd"),
            new Released(
                    "budgeting/V2_1__budgeting_schema.sql",
                    "cf637d74122f4c833142345a19e560a31c251702ead8836f6ef8ccfeb237f179"),
            new Released(
                    "planning/V3_1__planning_schema.sql",
                    "9301da456c321ee5a32381e685fd98999e47ee4d569857c7c0e80ec912d9b5e7"),
            new Released(
                    "accounting/V1_2__journal_recorded_by.sql",
                    "b85e62309933fbf996a356c1532243a6cca43f39c39164c32948a32c8fe3e19c"),
            new Released(
                    "accounting/V1_3__journal_entries_close_once_recorded.sql",
                    "d76372150b51b0f0b5d9a984fea31a793befaf9f664439978c9048a7a30e7446"),
            new Released(
                    "accounting/V1_4__journal_state_transitions.sql",
                    "1c8e880d5755659b57f28ca0f5e3b42667b61f74319a65ec8dbca70bdbc4b9d6"),
            new Released(
                    "accounting/V1_5__journal_transaction_is_complete.sql",
                    "d88de8f4c964c22e60178212650ef8d0528035ca5bd0297e04f9bc99dac0c2d8"));

    @Test
    void Given_the_migrations_on_the_classpath_When_each_is_hashed_Then_each_matches_the_digest_it_was_released_with()
            throws IOException {
        Map<String, String> found = new TreeMap<>();
        for (Resource script : scriptsOnTheClasspath()) {
            found.put(pathUnderMigrations(script), sha256(script));
        }
        Map<String, String> released = RELEASED.stream()
                .collect(Collectors.toMap(
                        Released::path,
                        Released::sha256,
                        (first, second) -> {
                            throw new IllegalStateException("a script is recorded twice as released");
                        },
                        TreeMap::new));

        assertThat(found)
                .as("a released migration is never edited, and a new one is recorded here when it ships;"
                        + " the owner's database refuses to start on a changed checksum")
                .isEqualTo(released);
    }

    /**
     * Within one context, each script released must carry a higher version than every one released
     * before it. Flyway runs out of order across contexts (ADR-035), so it would apply a {@code
     * V1_1_5} released after {@code V1_2} to the owner's database after {@code V1_2}, and to a fresh one
     * before it: two databases built by different paths from the same scripts.
     */
    @Test
    void
            Given_the_released_migrations_When_read_in_release_order_Then_versions_strictly_increase_within_each_context() {
        Map<String, List<Integer>> latestByDirectory = new HashMap<>();
        List<String> outOfOrder = new ArrayList<>();
        for (Released script : RELEASED) {
            List<Integer> version = version(script.path());
            List<Integer> latest = latestByDirectory.put(directory(script.path()), version);
            if (latest != null && VERSION_ORDER.compare(version, latest) <= 0) {
                outOfOrder.add(script.path() + " released after version " + latest);
            }
        }

        assertThat(outOfOrder)
                .as("scripts released at or below a version their context had already released")
                .isEmpty();
    }

    /**
     * Each context owns one major version (ADR-024), so a script whose major is another context's would
     * collide with that context's history: Flyway keeps one of two scripts sharing a version and leaves
     * the other out without an error. Read from the classpath, so a new script fails here before its
     * digest is recorded.
     */
    @Test
    void
            Given_the_migrations_on_the_classpath_When_their_versions_are_read_Then_each_major_is_the_one_its_context_owns()
                    throws IOException {
        List<String> misplaced = new ArrayList<>();
        Resource[] scripts = scriptsOnTheClasspath();
        for (Resource script : scripts) {
            String path = pathUnderMigrations(script);
            Integer owned = MAJOR_BY_DIRECTORY.get(directory(path));
            if (owned == null || !owned.equals(version(path).getFirst())) {
                misplaced.add(path + " (the directory owns major " + owned + ")");
            }
        }

        assertThat(scripts).as("a scan finding no migration proves nothing").isNotEmpty();
        assertThat(misplaced)
                .as("scripts whose major version is not their context's: accounting 1, budgeting 2, planning 3")
                .isEmpty();
    }

    /** One released script: its path under {@code db/migration/} and its SHA-256. */
    private record Released(String path, String sha256) {}

    private static final Comparator<List<Integer>> VERSION_ORDER = (left, right) -> {
        for (int part = 0; part < Math.min(left.size(), right.size()); part++) {
            int compared = Integer.compare(left.get(part), right.get(part));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.size(), right.size());
    };

    private static Resource[] scriptsOnTheClasspath() throws IOException {
        return new PathMatchingResourcePatternResolver().getResources("classpath*:" + MIGRATIONS + "**/V*.sql");
    }

    /** The context directory a script sits in, or the empty string for one directly under {@code db/migration/}. */
    private static String directory(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** {@code accounting/V1_1_5__x.sql} is {@code [1, 1, 5]}; a name Flyway would not version fails. */
    private static List<Integer> version(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        Matcher matcher = VERSIONED_SCRIPT.matcher(file);
        if (!matcher.matches()) {
            throw new IllegalStateException(path + " is not named V<version>__<description>.sql");
        }
        return Arrays.stream(matcher.group(1).split("_")).map(Integer::valueOf).toList();
    }

    private static String pathUnderMigrations(Resource script) throws IOException {
        String uri = script.getURI().toString();
        return uri.substring(uri.lastIndexOf(MIGRATIONS) + MIGRATIONS.length());
    }

    private static String sha256(Resource script) throws IOException {
        try (InputStream in = script.getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("every Java platform must provide SHA-256", absent);
        }
    }
}
