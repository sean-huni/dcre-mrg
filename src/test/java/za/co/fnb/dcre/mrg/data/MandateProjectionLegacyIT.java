package za.co.fnb.dcre.mrg.data;

import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-91 legacy-state gate for the CROSS-SERVICE migration race on the mandate projection
 * (testing.md: migration changes are tested against LEGACY database states, not only fresh
 * containers).
 *
 * <p>Ten M-services migrate the one dcre_man, each with its OWN liquibase history and lock
 * table, so NOTHING serializes their migrations. Nine of them carry an identical un-cascaded
 * guarded drop of the `mandate` projection at position 000. CockroachDB refuses an un-cascaded
 * drop while any view still selects from the table, so for the drop to be safe from ANY
 * service in ANY order, no view over `mandate` may exist at any instant during ANY of the ten
 * migrations. MRG used to build exactly such a view (man_ext_status, 003) and drop it again at
 * 006, which left a window in which a sibling's drop failed, and a window in which MRG built a
 * view over a table a sibling was dropping.</p>
 *
 * <p>The three reachable database states are pinned here: a STANDING database that still
 * carries the retired view over the projection, a HALF-MIGRATED one that carries the
 * projection but no view, and a FRESH one that carries neither. Each is built explicitly in
 * its own database inside the container the MRG suites already share, then the CURRENT mrg
 * changelog runs over it and must converge on: no view, no projection, no failure. One
 * database per scenario: the legacy seed is plain DDL and cannot be applied twice.</p>
 */
class MandateProjectionLegacyIT extends AbstractMrgCrdbIT {

    private static final String CURRENT = "classpath:db/changelog/db.changelog-master.xml";

    /**
     * The dangerous state: the projection still stands AND the retired view still selects from
     * it. Every sibling's 000 drop is refused here, which is the race in one assertion; MRG's
     * migration must retire the view before its own copy of that same drop runs.
     */
    @Test
    void aStandingDatabaseCarryingTheRetiredViewAndTheProjectionConverges() throws LiquibaseException {
        final JdbcTemplate legacy = database("legacy_man_projection_view");
        seedProjection(legacy);
        seedRetiredView(legacy);

        assertThatThrownBy(() -> legacy.execute("DROP TABLE mandate"))
                .hasMessageContaining("man_ext_status");

        migrate(legacy);

        assertThat(viewCount(legacy)).isZero();
        assertThat(projectionCount(legacy)).isZero();
        assertThat(exectype(legacy, "006-drop-man-ext-status")).isEqualTo("EXECUTED");
        assertThat(exectype(legacy, "mrg-000-drop-mandate-projection")).isEqualTo("EXECUTED");
    }

    /** Half migrated: 006 already retired the view, the projection is still there. */
    @Test
    void aHalfMigratedDatabaseCarryingTheProjectionAloneConverges() throws LiquibaseException {
        final JdbcTemplate legacy = database("legacy_man_projection_only");
        seedProjection(legacy);

        migrate(legacy);

        assertThat(viewCount(legacy)).isZero();
        assertThat(projectionCount(legacy)).isZero();
        assertThat(exectype(legacy, "006-drop-man-ext-status")).isEqualTo("MARK_RAN");
        assertThat(exectype(legacy, "mrg-000-drop-mandate-projection")).isEqualTo("EXECUTED");
    }

    /**
     * Fresh: the shared core mints the projection and retires it in the SAME run, exactly as
     * the nine siblings do, so a service booting after MRG can never resurrect it.
     */
    @Test
    void aFreshDatabaseMintsTheProjectionAndRetiresItInTheSameRun() throws LiquibaseException {
        final JdbcTemplate fresh = database("legacy_man_projection_fresh");

        migrate(fresh);

        assertThat(viewCount(fresh)).isZero();
        assertThat(projectionCount(fresh)).isZero();
        assertThat(exectype(fresh, "mrg-000-mandate")).isEqualTo("EXECUTED");
        assertThat(exectype(fresh, "mrg-000-drop-mandate-projection")).isEqualTo("EXECUTED");
        assertThat(exectype(fresh, "006-drop-man-ext-status")).isEqualTo("MARK_RAN");
    }

    /**
     * The other race direction: MRG must never BUILD a view over the projection, or a sibling
     * dropping the table mid-migration collides with it. The changeset that did is gone, so no
     * database, fresh or standing, has a history row for it.
     */
    @Test
    void theRetiredViewIsNeverRebuilt() {
        assertThat(historyCount(jdbc, "003-man-ext-status-view")).isZero();
        assertThat(viewCount(jdbc)).isZero();
    }

    /** MSR's projection shape, replayed: the table the standing cluster carries. */
    private void seedProjection(final JdbcTemplate legacy) {
        legacy.execute("""
                CREATE TABLE mandate (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    mandate_ref VARCHAR(35) NOT NULL,
                    contract_ref VARCHAR(14) NOT NULL,
                    creditor_account VARCHAR(32) NOT NULL,
                    debtor_account VARCHAR(32),
                    debtor_branch VARCHAR(16),
                    debtor_name VARCHAR(70),
                    max_collection_amount DECIMAL(18,2),
                    frequency VARCHAR(4),
                    collection_day SMALLINT,
                    state VARCHAR(16) NOT NULL,
                    start_date DATE,
                    expiry_date DATE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now())""");
        legacy.execute("CREATE INDEX ix_mandate_ref ON mandate (mandate_ref)");
    }

    /**
     * The retired view, in the column shape AND types 003 minted (client from the spine's
     * VARCHAR(16) client_token, then the projection's own two columns). The spine join arms are
     * irrelevant to what is being tested: CockroachDB refuses the un-cascaded drop for ANY
     * dependent view, and the dependency is the hazard.
     */
    private void seedRetiredView(final JdbcTemplate legacy) {
        legacy.execute("CREATE VIEW man_ext_status AS SELECT 'LEGACY'::VARCHAR(16) AS client,"
                + " mandate_ref, state FROM mandate");
    }

    private int viewCount(final JdbcTemplate target) {
        final Integer n = target.queryForObject("SELECT count(*) FROM information_schema.views"
                + " WHERE table_catalog = current_database() AND table_name = 'man_ext_status'",
                Integer.class);
        return n == null ? -1 : n;
    }

    private int projectionCount(final JdbcTemplate target) {
        final Integer n = target.queryForObject("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_catalog = current_database() AND table_name = 'mandate'",
                Integer.class);
        return n == null ? -1 : n;
    }

    private String exectype(final JdbcTemplate target, final String changeset) {
        return target.queryForObject("SELECT exectype FROM mrg_databasechangelog WHERE id = ?",
                String.class, changeset);
    }

    private int historyCount(final JdbcTemplate target, final String changeset) {
        final Integer n = target.queryForObject(
                "SELECT count(*) FROM mrg_databasechangelog WHERE id = ?", Integer.class, changeset);
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
    private void migrate(final JdbcTemplate legacy) throws LiquibaseException {
        final SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(legacy.getDataSource());
        liquibase.setChangeLog(CURRENT);
        liquibase.setDatabaseChangeLogTable("mrg_databasechangelog");
        liquibase.setDatabaseChangeLogLockTable("mrg_databasechangeloglock");
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.afterPropertiesSet();
    }
}
