package za.co.fnb.dcre.mrg.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import za.co.fnb.dcre.mrg.data.repo.CollectionOutcomeDao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cross-database read contract, from the READER's side (A-70).
 *
 * <p>[!CONVENTION-OVERRIDE] MRG reads {@code man_collection_outcome} out of {@code dcre_col} over a
 * second datasource, which breaks database-per-service
 * (https://microservices.io/patterns/data/database-per-service.html). The override is stated in
 * full, with its retirement condition, in the OWNING changelog:
 * {@code collections/crg/src/main/resources/db/changelog/2026/08/005-man-collection-outcome.xml}.
 * This class is its reader-side consequence.
 *
 * <p><b>Why this class exists.</b> On 2026-08-08 the view was deleted from CRG's v1 baseline and
 * nothing anywhere went red. CRG's suite was green because CRG never reads it. MRG's suite was
 * green because {@link ManColFixture} builds a faithful DOUBLE of the view rather than the real
 * one, so MRG's tests cannot observe the collections schema at all. The only thing that would have
 * failed was a production report window.
 *
 * <p>That double is the right call for the suspension BEHAVIOUR tests, which have no business
 * standing up CRG's whole projection graph. It is the wrong instrument for the DEPENDENCY, and the
 * two need different tests. So this one asserts the property the double cannot: that an absent view
 * is LOUD. MRG's failure mode today is a crash per report window, and a crash is a bad outcome with
 * a good property, which is that somebody finds out. Do not "improve" this into a degradation to
 * an empty result: a mandate that should be suspended and silently is not is strictly worse than a
 * window that fails and is retried.
 *
 * <p>What this class cannot do, stated rather than implied: it cannot see CRG's changelog, so it
 * cannot fail when the view is removed THERE. The tripwire for that lives in CRG
 * ({@code ManCollectionOutcomeIT.theViewExistsAndCarriesTheColumnsMrgReads}). Two repositories,
 * two halves, and neither half is sufficient alone. Generating this contract from a published
 * schema is what eventually replaces both, and it is step 2 of the target design in 005.
 */
@TestPropertySource(properties = "spring.batch.job.name=mrgSuspendJob")
class CollectionOutcomeContractIT extends AbstractMrgCrdbIT {

    static final JdbcTemplate COL_JDBC;

    static {
        exec("CREATE DATABASE IF NOT EXISTS dcre_col");
        COL_JDBC = new JdbcTemplate(
                new DriverManagerDataSource(colUrl(), CRDB.getUsername(), CRDB.getPassword()));
    }

    static String colUrl() {
        return CRDB.getJdbcUrl().replaceFirst("(jdbc:postgresql://[^/]+/)[^?]+", "$1dcre_col");
    }

    static void exec(final String sql) {
        try (Connection c = DriverManager.getConnection(
                CRDB.getJdbcUrl(), CRDB.getUsername(), CRDB.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void colProps(final DynamicPropertyRegistry registry) {
        registry.add("dcre.col-db-url", CollectionOutcomeContractIT::colUrl);
        registry.add("dcre.col-db-user", CRDB::getUsername);
        registry.add("dcre.col-db-password", CRDB::getPassword);
    }

    @Autowired
    CollectionOutcomeDao outcomes;

    @BeforeEach
    void colSchema() {
        ManColFixture.createManCollectionOutcome(COL_JDBC);
    }

    /** Leave the shared dcre_col as it was found: the next class in the suite reads it too. */
    @AfterEach
    void restoreView() {
        ManColFixture.createManCollectionOutcome(COL_JDBC);
    }

    /**
     * The positive control (verification.md 11a). Without it, the absence assertion below could
     * pass because the DAO can never find anything, and an instrument that cannot find a present
     * row proves nothing by failing to find an absent one.
     */
    @Test
    void theDaoReadsTerminalFailuresNewestFirstWhenTheViewIsPresent() {
        final Instant now = Instant.now();
        ManColFixture.insertCollectionOutcome(COL_JDBC, "MND_CONTRACT", "ACSC", now.minus(3, ChronoUnit.HOURS));
        ManColFixture.insertCollectionOutcome(COL_JDBC, "MND_CONTRACT", "RJCT", now.minus(2, ChronoUnit.HOURS));
        ManColFixture.insertCollectionOutcome(COL_JDBC, "MND_CONTRACT", "CANC", now.minus(1, ChronoUnit.HOURS));

        assertThat(outcomes.recentTerminalFailures("MND_CONTRACT", 3))
                .as("newest first: CANC and RJCT are TERMINAL_NON_SUCCESS, ACSC is a terminal success")
                .containsExactly(true, true, false);
        assertThat(outcomes.recentTerminalFailures("MND_NOT_SEEDED", 3))
                .as("a mandate with no collections is an empty result, not an error")
                .isEmpty();
    }

    /**
     * The property this class exists for. Dropping the view models exactly what CRG's v1 baseline
     * did on 2026-08-08, and the assertion is that the DAO RAISES rather than returning nothing.
     *
     * <p>The assertion names the relation, not merely "an exception": a bare non-empty-throw
     * assertion would also pass if the datasource were misconfigured, the database absent, or the
     * container dead, which are three ways for this test to succeed without testing anything.
     */
    @Test
    void anAbsentViewIsLoudRatherThanAnEmptySuspensionResult() {
        COL_JDBC.execute("DROP VIEW IF EXISTS man_collection_outcome");

        assertThatThrownBy(() -> outcomes.recentTerminalFailures("MND_CONTRACT", 3))
                .as("an absent man_collection_outcome must fail the report window, never degrade"
                        + " to 'no suspensions'")
                .isInstanceOf(BadSqlGrammarException.class)
                .hasMessageContaining("man_collection_outcome");
    }
}
