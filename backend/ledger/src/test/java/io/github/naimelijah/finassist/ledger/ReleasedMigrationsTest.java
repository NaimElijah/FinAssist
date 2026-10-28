package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
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
 * digest is recorded below — the moment to decide that it is released. Never change a recorded
 * digest; write the next migration instead.
 */
class ReleasedMigrationsTest {

    private static final String MIGRATIONS = "db/migration/";

    /** Every released script, by its path under {@code db/migration/}, and the SHA-256 it shipped with. */
    private static final Map<String, String> RELEASED = Map.of(
            "accounting/V1_1__accounting_schema.sql",
                    "511c03cf68f8880a2c76b278bd281bc81a22e361c436f1c1c9374c5ae2d4f2bd",
            "accounting/V1_2__journal_recorded_by.sql",
                    "b85e62309933fbf996a356c1532243a6cca43f39c39164c32948a32c8fe3e19c",
            "budgeting/V2_1__budgeting_schema.sql", "cf637d74122f4c833142345a19e560a31c251702ead8836f6ef8ccfeb237f179",
            "planning/V3_1__planning_schema.sql", "9301da456c321ee5a32381e685fd98999e47ee4d569857c7c0e80ec912d9b5e7");

    @Test
    void Given_the_migrations_on_the_classpath_When_each_is_hashed_Then_each_matches_the_digest_it_was_released_with()
            throws IOException {
        Map<String, String> found = new TreeMap<>();
        for (Resource script :
                new PathMatchingResourcePatternResolver().getResources("classpath*:" + MIGRATIONS + "**/V*.sql")) {
            found.put(pathUnderMigrations(script), sha256(script));
        }

        assertThat(found)
                .as("a released migration is never edited, and a new one is recorded here when it ships;"
                        + " the owner's database refuses to start on a changed checksum")
                .isEqualTo(new TreeMap<>(RELEASED));
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
