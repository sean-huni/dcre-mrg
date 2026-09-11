package za.co.fnb.dcre.mrg.data;

import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-107 v1 baseline gate. Replaces MandateProjectionLegacyIT, which pinned the three
 * reachable states of a MIGRATION (a standing cluster carrying the retired man_ext_status
 * view, a half-migrated one carrying the mandate projection alone, and a fresh one). The
 * direct cut-over drops every DCRE database, so those states are unreachable and asserting
 * about them proves nothing about what ships.
 *
 * <p>What is worth asserting instead is that the baseline IS a baseline. Each test below
 * would go red if the retrofit apparatus crept back, which is the only failure mode a v1
 * changelog has: the shapes v1 never mints must never appear, no changeset may exist whose
 * whole job is to undo another, and a guard must not silently swallow a create that failed.</p>
 *
 * <p>The changelog is run through SpringLiquibase against its own database inside the
 * container the MRG suites already share, exactly as production runs it, including MRG's
 * per-service history and lock tables.</p>
 */
class ManV1BaselineIT extends AbstractMrgCrdbIT {

    private static final String CURRENT = "classpath:db/changelog/db.changelog-master.xml";

    /**
     * The shapes SCRUM-91 retired and v1 simply never creates. The old suite proved they were
     * DROPPED; the v1 claim is stronger and cheaper, that they are never minted in the first
     * place, so no service booting in any order can find one standing.
     */
    @Test
    void theBaselineNeverMintsTheRetiredProjectionOrItsView() throws LiquibaseException {
        final JdbcTemplate fresh = database("v1_baseline_shapes");

        migrate(fresh);

        assertThat(tableCount(fresh, "mandate")).isZero();
        assertThat(viewCount(fresh, "man_ext_status")).isZero();
        // The spine and the derived stack it feeds ARE minted: a green above must mean "the
        // shapes are absent", never "the migration did nothing".
        assertThat(tableCount(fresh, "mandate_request_entry")).isOne();
        assertThat(viewCount(fresh, "mandate_current_status")).isOne();
    }

    /**
     * No changeset exists whose only job is to undo another. This is the structural guard on
     * the whole class of scar tissue: a create-then-drop pair, a nullability relaxation, an
     * index swap. Any of them reappearing means the changelog has started describing a
     * migration again.
     */
    @Test
    void theBaselineCarriesNoTeardownChangesets() throws LiquibaseException {
        final JdbcTemplate fresh = database("v1_baseline_teardown");

        migrate(fresh);

        final List<String> teardown = fresh.queryForList(
                "SELECT id FROM mrg_databasechangelog WHERE id ILIKE '%drop%'", String.class);
        assertThat(teardown).isEmpty();
    }

    /**
     * On a database nothing else has touched, every changeset must actually EXECUTE. A
     * MARK_RAN here would mean a precondition fired with no second writer in sight, which is
     * either a dead guard or, worse, a create that failed and was swallowed.
     *
     * <p>Two changesets are exempt, and both are NAMED rather than pattern-matched so that a
     * new conditional changeset cannot join the exemption silently.</p>
     *
     * <ul>
     *   <li>{@code 007-man-ctv-view-grant} is load-bearing on a v1 database for a reason
     *       unrelated to migration: a Testcontainers cluster has no ctv role, a provisioned
     *       cluster does.</li>
     *   <li>{@code mrg-v2-report-type-rename} repairs a live database whose man_report was
     *       created before the report_type to type rename and then had the create marked as
     *       applied by a tableExists guard. On a fresh database the column is already named
     *       type, so its columnExists precondition fails and MARK_RAN is the CORRECT outcome:
     *       an EXECUTED rename here would mean the baseline had started minting the stale
     *       spelling again.</li>
     * </ul>
     */
    @Test
    void everyChangesetExecutesOnADatabaseWithNoOtherWriter() throws LiquibaseException {
        final JdbcTemplate fresh = database("v1_baseline_exectype");

        migrate(fresh);

        final List<String> markRan = fresh.queryForList(
                "SELECT id FROM mrg_databasechangelog WHERE exectype = 'MARK_RAN' ORDER BY id",
                String.class);
        assertThat(markRan)
                .containsExactly("007-man-ctv-view-grant", "mrg-v2-report-type-rename");
    }

    /**
     * Booting the service twice against the same database applies nothing further. This is
     * what the removed IF NOT EXISTS clauses and re-run guards were reaching for, and the
     * history table provides it on its own once no changeset is content-unstable.
     */
    @Test
    void asecondMigrationOfTheSameDatabaseAppliesNothing() throws LiquibaseException {
        final JdbcTemplate fresh = database("v1_baseline_rerun");

        migrate(fresh);
        final int afterFirst = historySize(fresh);
        migrate(fresh);

        assertThat(afterFirst).isPositive();
        assertThat(historySize(fresh)).isEqualTo(afterFirst);
    }

    private int tableCount(final JdbcTemplate target, final String table) {
        return count(target, "SELECT count(*) FROM information_schema.tables"
                + " WHERE table_catalog = current_database() AND table_name = ?", table);
    }

    private int viewCount(final JdbcTemplate target, final String view) {
        return count(target, "SELECT count(*) FROM information_schema.views"
                + " WHERE table_catalog = current_database() AND table_name = ?", view);
    }

    private int historySize(final JdbcTemplate target) {
        return count(target, "SELECT count(*) FROM mrg_databasechangelog");
    }

    private int count(final JdbcTemplate target, final String sql, final Object... args) {
        final Integer n = target.queryForObject(sql, Integer.class, args);
        return n == null ? -1 : n;
    }

    /** One logical database per scenario inside the container the MRG suites already share. */
    private JdbcTemplate database(final String name) {
        new JdbcTemplate(dataSource(CRDB.getDatabaseName()))
                .execute("CREATE DATABASE IF NOT EXISTS " + name);
        return new JdbcTemplate(dataSource(name));
    }

    private DataSource dataSource(final String name) {
        final String url = CRDB.getJdbcUrl().replace("/" + CRDB.getDatabaseName(), "/" + name);
        return new DriverManagerDataSource(url, CRDB.getUsername(), CRDB.getPassword());
    }

    /** Production integration: SpringLiquibase + MRG's per-service history table. */
    private void migrate(final JdbcTemplate target) throws LiquibaseException {
        final SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(target.getDataSource());
        liquibase.setChangeLog(CURRENT);
        liquibase.setDatabaseChangeLogTable("mrg_databasechangelog");
        liquibase.setDatabaseChangeLogLockTable("mrg_databasechangeloglock");
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.afterPropertiesSet();
    }
}
