package za.co.fnb.dcre.mrg.data;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-107 v1 shape gate for mandate_status_history. Replaces ManStatusHistoryLegacyIT,
 * which constructed MSR's original table shape by hand and proved that four convergence
 * changesets moved it to the derived one. The direct cut-over drops every DCRE database, so
 * that starting shape is unreachable, the four changesets are gone, and 008 now mints the
 * final shape in a single createTable.
 *
 * <p>The claims worth keeping are the ones about the SHAPE itself rather than the route to
 * it, and they are asserted here against the real Liquibase-migrated schema the whole MRG
 * suite shares:</p>
 *
 * <ol>
 *   <li>source_leg and response_file are NULLABLE. Under derivation there is no leg and no
 *       response file, so a NOT NULL on either makes every derived append impossible.</li>
 *   <li>The unique key is (mandate_ref, to_state, report_id). Keyed on response_file it
 *       admits unlimited duplicates the moment that column is NULL, because NULLs do not
 *       collide in a unique index on CockroachDB or Postgres.</li>
 *   <li>Which means the thing the key exists for: replaying a report window appends the
 *       transition once, not twice.</li>
 * </ol>
 *
 * <p>Point 3 is the load-bearing one and is deliberately not left to the presence of an index
 * name: it writes the same derived row twice and counts.</p>
 */
class ManStatusHistoryShapeIT extends AbstractMrgCrdbIT {

    @Test
    void theDerivedColumnsAreNullableBecauseADerivedRowCarriesNeither() {
        assertThat(nullabilityOf("source_leg")).isEqualTo("YES");
        assertThat(nullabilityOf("response_file")).isEqualTo("YES");
        assertThat(nullabilityOf("report_id")).isEqualTo("YES");
    }

    @Test
    void theIdentityIsReportScopedAndTheSupersededResponseFileKeyIsAbsent() {
        assertThat(constraintCount("uq_man_status_history_derived")).isEqualTo(1);
        assertThat(constraintCount("uq_man_status_history_identity")).isZero();
    }

    /**
     * Replaying a report window is a zero-duplicate no-op. Two identical derived appends under
     * one report id leave one row, and it carries no leg.
     */
    @Test
    void replayingAReportWindowAppendsTheTransitionOnce() {
        final UUID reportId = UUID.randomUUID();
        final String mandateRef = uniqueRef();

        appendDerived(mandateRef, reportId);
        appendDerived(mandateRef, reportId);
        appendDerived(mandateRef, reportId);

        assertThat(rowsFor(mandateRef)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_leg FROM mandate_status_history"
                + " WHERE mandate_ref = ?", String.class, mandateRef)).isNull();
    }

    /**
     * The other side of the same key, without which the test above would pass on a table that
     * simply refuses every second write: a DIFFERENT report window observing the same
     * transition is a distinct row, because the report id is part of the identity.
     */
    @Test
    void aLaterReportWindowObservingTheSameTransitionIsADistinctRow() {
        final String mandateRef = uniqueRef();

        appendDerived(mandateRef, UUID.randomUUID());
        appendDerived(mandateRef, UUID.randomUUID());

        assertThat(rowsFor(mandateRef)).isEqualTo(2);
    }

    /**
     * A mandate_ref unique to one test, inside VARCHAR(35). The suite shares one database, so
     * every case scopes its assertions to its own ref rather than counting the whole table.
     */
    private String uniqueRef() {
        return "SHP-" + UUID.randomUUID().toString().substring(0, 18);
    }

    /** The derived append shape: no leg, no response file, guarded on the report-scoped identity. */
    private void appendDerived(final String mandateRef, final UUID reportId) {
        jdbc.update("INSERT INTO mandate_status_history (mandate_ref, from_state, to_state,"
                        + " report_id) VALUES (?,?,?,?)"
                        + " ON CONFLICT (mandate_ref, to_state, report_id) DO NOTHING",
                mandateRef, "REQUESTED", "ACCP", reportId);
    }

    private int rowsFor(final String mandateRef) {
        final Integer n = jdbc.queryForObject("SELECT count(*) FROM mandate_status_history"
                + " WHERE mandate_ref = ?", Integer.class, mandateRef);
        return n == null ? -1 : n;
    }

    private String nullabilityOf(final String column) {
        return jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns"
                        + " WHERE table_name = 'mandate_status_history' AND column_name = ?",
                String.class, column);
    }

    private int constraintCount(final String name) {
        final Integer n = jdbc.queryForObject("SELECT count(*) FROM pg_indexes"
                + " WHERE tablename = 'mandate_status_history' AND indexname = ?", Integer.class, name);
        return n == null ? -1 : n;
    }
}
