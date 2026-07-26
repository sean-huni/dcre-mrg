package za.co.fnb.dcre.mrg.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-91 cutover: the written mandate projection is gone. Every attribute it carried lives
 * on the MRR request spine (which is why the projection was redundant), and its state is
 * derived by mandate_effective_status / mandate_current_status.
 *
 * <p>This runs on the REAL Liquibase-migrated schema, so it also proves the drop is ordered
 * safely: the derived views are created before the table goes, and no view is left dangling
 * over a dropped relation (CockroachDB would refuse the drop otherwise).</p>
 */
class ProjectionRemovalIT extends AbstractMrgCrdbIT {

    @Test
    void theMandateProjectionTableIsGone() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_catalog = current_database() AND table_name = 'mandate'",
                Integer.class)).isZero();
    }

    @Test
    void theReasonCodeReferenceTableSurvives() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mandate_reason_code", Integer.class))
                .isEqualTo(9);
    }

    @Test
    void theDerivedViewsStillResolveWithoutTheProjection() {
        seedSpine("CL01", "MND-70", "MREQ-70");
        seedPbsr("MREQ-70", "ACCP", null);

        assertThat(stateOf("MREQ-70")).isEqualTo("ACCP");
        assertThat(currentStateOf("MND-70")).isEqualTo("ACCP");
        assertThat(rowCountOf("mnd_ext_status", "MND-70")).isEqualTo(1);
        assertThat(rowCountOf("man_ctv_view", "MND-70")).isEqualTo(1);
    }

    /** The drop is a real migration step, not a fixture artefact: MRG's history records it. */
    @Test
    void theDropChangesetRanAsPartOfTheMigration() {
        assertThat(jdbc.queryForObject(
                "SELECT exectype FROM mrg_databasechangelog WHERE id = '009-drop-mandate-projection'",
                String.class)).isIn("EXECUTED", "MARK_RAN");
    }
}
