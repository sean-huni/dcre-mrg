package za.co.fnb.dcre.mrg.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The written mandate projection is absent. Every attribute it carried lives on the MRR
 * request spine (which is why the projection was redundant), and its state is derived by
 * mandate_effective_status / mandate_current_status.
 *
 * <p>SCRUM-107: under v1 the projection is never created, so this is no longer a claim about
 * a DROP being ordered safely. It is a claim about the derived stack standing on its own,
 * which is why the third test seeds and reads it rather than only counting rows in
 * information_schema. The fourth test, which asserted that
 * {@code 009-drop-mandate-projection} appeared in MRG's history, is deleted with the
 * changeset: v1 has no teardown to record. That the baseline mints no teardown at all is
 * asserted structurally in {@link ManV1BaselineIT}.</p>
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
}
