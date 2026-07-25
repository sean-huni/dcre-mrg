package za.co.fnb.dcre.mrg.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-91 bootstrap-order guard contract. MRG is clock-launched and may migrate a fresh
 * dcre_man before MIX/MSX/MPX ever run, so 004-man-views pre-creates the three response
 * tables its views read. That pre-create must be byte-equivalent to the owning services'
 * (mix/msx/mpx 001-man-&lt;leg&gt;-resp.xml): if the shapes drift, the MARK_RAN tableExists
 * guards stop converging and whichever service runs SECOND silently reads a table it did
 * not expect. This locks the shape on the MRG side so a divergence fails here, not live.
 *
 * <p>Also asserts the pick windows are index-backed: SCRUM-55 (live 2026-07-16) proved an
 * unindexed correlated scan of the collections equivalent hangs the AGT trigger scan.</p>
 */
class MndViewBootstrapIT extends AbstractMrgCrdbIT {

    /** The owners' exact column set, identical across the three legs by design. */
    private static final List<String> LEG_COLUMNS = List.of(
            "id:uuid",
            "response_file:character varying(128)",
            "orgnl_msg_id:character varying(35)",
            "mndt_id:character varying(35)",
            "mndt_req_id:character varying(35)",
            "e2e:character varying(35)",
            "status:character varying(8)",
            "reason:character varying(8)",
            "version:bigint",
            "created_at:timestamp with time zone",
            "updated_at:timestamp with time zone");

    @ParameterizedTest
    @ValueSource(strings = {"man_isr_resp", "man_sbsr_resp", "man_pbsr_resp"})
    void thePreCreatedLegTableCarriesTheOwnersExactColumnSet(final String table) {
        final List<String> columns = jdbc.queryForList(
                "SELECT column_name || ':' || data_type"
                        + " || COALESCE('(' || character_maximum_length::STRING || ')', '')"
                        + " FROM information_schema.columns WHERE table_name = ?"
                        + " ORDER BY ordinal_position", String.class, table);

        assertThat(columns).containsExactlyElementsOf(LEG_COLUMNS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"isr", "sbsr", "pbsr"})
    void thePreCreatedLegTableCarriesTheOwnersExactUniqueConstraintName(final String leg) {
        final List<String> unique = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints"
                        + " WHERE table_name = ? AND constraint_type = 'UNIQUE'",
                String.class, "man_%s_resp".formatted(leg));

        assertThat(unique).containsExactly("uq_man_%s_resp_file_mndt_req".formatted(leg));
    }

    /** The request-leg verdict sink is a view source too: same contract, owner is MRV. */
    @Test
    void thePreCreatedValidationLogCarriesMrvsExactShape() {
        final List<String> columns = jdbc.queryForList(
                "SELECT column_name || ':' || data_type"
                        + " || COALESCE('(' || character_maximum_length::STRING || ')', '')"
                        + " FROM information_schema.columns WHERE table_name = 'man_validation_log'"
                        + " ORDER BY ordinal_position", String.class);
        final List<String> unique = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints"
                        + " WHERE table_name = 'man_validation_log' AND constraint_type = 'UNIQUE'",
                String.class);

        assertThat(columns).containsExactly("id:uuid", "arrival_id:uuid", "sequence:bigint",
                "outcome:character varying(32)", "detail:character varying(256)");
        assertThat(unique).containsExactly("uq_man_validation_arrival_sequence");
    }

    @ParameterizedTest
    @ValueSource(strings = {"isr", "sbsr", "pbsr"})
    void thePickWindowIsIndexBackedOnPartitionThenNewestFirst(final String leg) {
        final List<String> index = jdbc.queryForList(
                "SELECT column_name || ':' || direction FROM information_schema.statistics"
                        + " WHERE index_name = ? ORDER BY seq_in_index", String.class,
                "ix_man_%s_pick".formatted(leg));

        // Trailing implicit PK column is CRDB's own; the leading pair is the contract.
        assertThat(index).startsWith("mndt_req_id:ASC", "created_at:DESC");
    }
}
