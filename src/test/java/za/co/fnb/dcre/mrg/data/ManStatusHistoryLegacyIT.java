package za.co.fnb.dcre.mrg.data;

import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-91 legacy-state migration gate for adopting mandate_status_history from MSR
 * (testing.md: migration changes are tested against LEGACY database states, not only fresh
 * containers). The standing dcre_man already carries the table in MSR's original shape:
 * source_leg and response_file NOT NULL, no report_id, and the old
 * uq_man_status_history_identity over (mandate_ref, to_state, response_file). A fresh
 * Testcontainers database creates none of that, so it can never catch a broken migration.
 *
 * <p>The legacy state is therefore constructed explicitly, exactly as MSR's
 * 001-mandate-projection.xml minted it, in its own database inside the container the MRG
 * suites already share; then the CURRENT mrg changelog runs over it and must converge on the
 * derived shape while leaving MSR's historic evidence intact. One database per scenario: the
 * legacy seed is plain DDL and cannot be applied twice.</p>
 */
class ManStatusHistoryLegacyIT extends AbstractMrgCrdbIT {

    private static final String CURRENT = "classpath:db/changelog/db.changelog-master.xml";

    /** The four changesets that exist ONLY to converge MSR's shape; the create is not one of them. */
    private static final List<String> CONVERGENCE = List.of(
            "008-man-status-history-source-leg-nullable",
            "008-man-status-history-response-file-nullable",
            "008-man-status-history-report-id",
            "008-drop-man-status-history-msr-identity");

    @Test
    void anMsrShapedTableConvergesAndThenAcceptsADerivedRow() throws LiquibaseException {
        final JdbcTemplate legacy = migratedLegacyDatabase("legacy_man_status");

        assertThat(nullabilityOf(legacy, "source_leg")).isEqualTo("YES");
        assertThat(nullabilityOf(legacy, "response_file")).isEqualTo("YES");
        assertThat(nullabilityOf(legacy, "report_id")).isEqualTo("YES");
        assertThat(constraintCount(legacy, "uq_man_status_history_identity")).isZero();
        assertThat(constraintCount(legacy, "uq_man_status_history_derived")).isEqualTo(1);

        // MSR's historic evidence is preserved: dropping the columns would destroy the audit
        assertThat(legacy.queryForObject("SELECT source_leg FROM mandate_status_history"
                + " WHERE mandate_ref = 'LEG-1'", String.class)).isEqualTo("PBSR");
        assertThat(legacy.queryForObject("SELECT response_file FROM mandate_status_history"
                + " WHERE mandate_ref = 'LEG-1'", String.class)).isEqualTo("LEG-1_PBSR.xml");

        // and the migrated table accepts a derived append, idempotent on the new identity
        final UUID reportId = UUID.randomUUID();
        appendDerived(legacy, reportId);
        appendDerived(legacy, reportId);

        assertThat(legacy.queryForObject("SELECT count(*) FROM mandate_status_history"
                + " WHERE mandate_ref = 'DRV-1'", Integer.class)).isEqualTo(1);
        assertThat(legacy.queryForObject("SELECT source_leg FROM mandate_status_history"
                + " WHERE mandate_ref = 'DRV-1'", String.class)).isNull();
    }

    /**
     * The guards are two-sided, which is the whole point of guarding on schema state: the four
     * convergence changesets EXECUTE against MSR's shape and MARK_RAN on a fresh database whose
     * create already minted the derived shape. The fresh side is read off the shared container,
     * migrated by the production Liquibase integration when the context started.
     */
    @Test
    void theConvergenceChangesetsExecuteOnLegacyAndMarkRanOnFresh() throws LiquibaseException {
        final JdbcTemplate legacy = migratedLegacyDatabase("legacy_man_status_guards");

        for (final String changeset : CONVERGENCE) {
            assertThat(exectype(legacy, changeset)).as(changeset).isEqualTo("EXECUTED");
            assertThat(exectype(jdbc, changeset)).as(changeset).isEqualTo("MARK_RAN");
        }
        assertThat(exectype(legacy, "008-man-status-history")).isEqualTo("MARK_RAN");
        assertThat(exectype(jdbc, "008-man-status-history")).isEqualTo("EXECUTED");
    }

    /**
     * The HALF-MIGRATED state (testing.md requires it alongside the legacy end state and the
     * fresh one). The chain is six changesets and Liquibase commits each one on its own, so
     * there is no instant at which the six are atomic; MRG migrates at pod start, so a kill
     * between two of them (eviction, node loss, the chaos gate's SIGKILL) leaves a durable
     * intermediate. This is the one the changelog itself names: "a kill after the first ALTER
     * committed". source_leg is ALREADY relaxed and nothing else is done: response_file is
     * still NOT NULL, report_id is absent, MSR's identity still stands, the derived identity
     * does not exist.
     *
     * <p>It is seeded with NO history rows, which is the harder half of that state and the one
     * the guards actually claim: every precondition must decide from SCHEMA STATE alone rather
     * than read the answer out of mrg_databasechangelog. That is not a contrivance, it is the
     * standing cluster's own situation, where MSR created the table under a DIFFERENT service's
     * history table and MRG's history starts empty.</p>
     *
     * <p>So the chain splits: the create and the source_leg relax MARK_RAN, the other four
     * EXECUTE, and the database converges on exactly the shape the full-legacy and fresh paths
     * reach.</p>
     */
    @Test
    void aHalfMigratedTableWithOnlyTheFirstAlterAppliedConverges() throws LiquibaseException {
        final JdbcTemplate legacy = database("legacy_man_status_half");
        seedMsrShapedTable(legacy);
        legacy.execute("ALTER TABLE mandate_status_history ALTER COLUMN source_leg DROP NOT NULL");

        migrate(legacy);

        assertThat(exectype(legacy, "008-man-status-history")).isEqualTo("MARK_RAN");
        assertThat(exectype(legacy, "008-man-status-history-source-leg-nullable")).isEqualTo("MARK_RAN");
        assertThat(exectype(legacy, "008-man-status-history-response-file-nullable")).isEqualTo("EXECUTED");
        assertThat(exectype(legacy, "008-man-status-history-report-id")).isEqualTo("EXECUTED");
        assertThat(exectype(legacy, "008-drop-man-status-history-msr-identity")).isEqualTo("EXECUTED");
        assertThat(exectype(legacy, "008-man-status-history-derived-identity")).isEqualTo("EXECUTED");

        assertThat(nullabilityOf(legacy, "source_leg")).isEqualTo("YES");
        assertThat(nullabilityOf(legacy, "response_file")).isEqualTo("YES");
        assertThat(nullabilityOf(legacy, "report_id")).isEqualTo("YES");
        assertThat(constraintCount(legacy, "uq_man_status_history_identity")).isZero();
        assertThat(constraintCount(legacy, "uq_man_status_history_derived")).isEqualTo(1);

        // the ALTER that survived the kill did not cost MSR's evidence either
        assertThat(legacy.queryForObject("SELECT source_leg FROM mandate_status_history"
                + " WHERE mandate_ref = 'LEG-1'", String.class)).isEqualTo("PBSR");

        final UUID reportId = UUID.randomUUID();
        appendDerived(legacy, reportId);
        appendDerived(legacy, reportId);
        assertThat(legacy.queryForObject("SELECT count(*) FROM mandate_status_history"
                + " WHERE mandate_ref = 'DRV-1'", Integer.class)).isEqualTo(1);
    }

    private JdbcTemplate migratedLegacyDatabase(final String name) throws LiquibaseException {
        final JdbcTemplate legacy = database(name);
        seedMsrShapedTable(legacy);
        migrate(legacy);
        return legacy;
    }

    /** MSR 001-mandate-status-history, replayed: the shape the standing cluster carries. */
    private void seedMsrShapedTable(final JdbcTemplate legacy) {
        legacy.execute("""
                CREATE TABLE mandate_status_history (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    mandate_ref VARCHAR(35) NOT NULL,
                    from_state VARCHAR(16) NOT NULL,
                    to_state VARCHAR(16) NOT NULL,
                    source_leg VARCHAR(16) NOT NULL,
                    reason_code VARCHAR(8),
                    response_file VARCHAR(128) NOT NULL,
                    at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now())""");
        legacy.execute("ALTER TABLE mandate_status_history ADD CONSTRAINT"
                + " uq_man_status_history_identity UNIQUE (mandate_ref, to_state, response_file)");
        legacy.execute("CREATE INDEX ix_man_status_history_ref"
                + " ON mandate_status_history (mandate_ref)");
        legacy.update("INSERT INTO mandate_status_history (mandate_ref, from_state, to_state,"
                        + " source_leg, reason_code, response_file) VALUES (?,?,?,?,?,?)",
                "LEG-1", "PDNG", "ACCP", "PBSR", null, "LEG-1_PBSR.xml");
    }

    /** The derived append shape: no leg, no response file, guarded on the report-scoped identity. */
    private void appendDerived(final JdbcTemplate legacy, final UUID reportId) {
        legacy.update("INSERT INTO mandate_status_history (mandate_ref, from_state, to_state,"
                        + " report_id) VALUES (?,?,?,?)"
                        + " ON CONFLICT (mandate_ref, to_state, report_id) DO NOTHING",
                "DRV-1", "REQUESTED", "ACCP", reportId);
    }

    private String nullabilityOf(final JdbcTemplate legacy, final String column) {
        return legacy.queryForObject("SELECT is_nullable FROM information_schema.columns"
                        + " WHERE table_name = 'mandate_status_history' AND column_name = ?",
                String.class, column);
    }

    private int constraintCount(final JdbcTemplate legacy, final String name) {
        final Integer n = legacy.queryForObject("SELECT count(*) FROM pg_indexes"
                + " WHERE tablename = 'mandate_status_history' AND indexname = ?", Integer.class, name);
        return n == null ? -1 : n;
    }

    private String exectype(final JdbcTemplate target, final String changeset) {
        return target.queryForObject("SELECT exectype FROM mrg_databasechangelog WHERE id = ?",
                String.class, changeset);
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
