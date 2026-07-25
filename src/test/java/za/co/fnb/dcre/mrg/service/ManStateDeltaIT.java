package za.co.fnb.dcre.mrg.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import za.co.fnb.dcre.mrg.data.AbstractMrgCrdbIT;
import za.co.fnb.dcre.mrg.data.model.ManStateRow;
import za.co.fnb.dcre.mrg.data.repo.ManWatermarkRepo;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-91: MRG's delta source moves from man_ext_status (projection-dressing over the
 * MSR-written mandate table) to the derived views. The watermark contract is unchanged:
 * report a mandate only when its state differs from the last reported one.
 *
 * <p>GRAIN: the delta is keyed (client, mandate_ref), so it reads mandate_current_status,
 * the per-MANDATE collapse, not the per-INSTRUCTION mandate_effective_status. The three
 * grain tests below are the defect that proved it.</p>
 *
 * <p>Each test owns its own client token. The suite shares one container, so a delta read
 * scoped to a shared client would see other tests' mandates and assert nothing.</p>
 */
class ManStateDeltaIT extends AbstractMrgCrdbIT {

    private static final int SLICE = 100;

    @Autowired
    ManWatermarkRepo watermarks;

    @Test
    void aNewlyActiveMandateIsADelta() {
        seedSpine("CLD01", "MND-40", "MREQ-40");
        seedPbsr("MREQ-40", "ACCP", null);

        assertThat(deltaRefs("CLD01")).containsExactly("MND-40");
    }

    @Test
    void anAlreadyReportedStateIsNotADelta() {
        seedSpine("CLD02", "MND-41", "MREQ-41");
        seedPbsr("MREQ-41", "ACCP", null);
        seedWatermark("CLD02", "MND-41", "ACCP");

        assertThat(deltaRefs("CLD02")).isEmpty();
    }

    /**
     * The one signal a view cannot derive. A suspension lands as a mandate_override record,
     * so MRG only reports it if its delta reads the DERIVED view; man_ext_status projected
     * the written mandate table and had no override awareness at all.
     */
    @Test
    void aSuspensionOverrideIsADeltaAgainstThePreviousActiveState() {
        seedSpine("CLD03", "MND-42", "MREQ-42");
        seedPbsr("MREQ-42", "ACCP", null);
        seedWatermark("CLD03", "MND-42", "ACCP");
        seedOverride("MND-42", "SUSPENDED", "MS03", "COLLECTION_FAILURE", "2026-07-25T08:00:00Z");

        assertThat(deltaRefs("CLD03")).containsExactly("MND-42");
    }

    /** A resend ignores the watermark and re-reads every current state from the derived view. */
    @Test
    void aResendRangeSliceIgnoresTheWatermark() {
        seedSpine("CLD04", "MND-43", "MREQ-43");
        seedPbsr("MREQ-43", "ACCP", null);
        seedWatermark("CLD04", "MND-43", "ACCP");

        assertThat(watermarks.findRangeSlice("CLD04", "", SLICE))
                .extracting(ManStateRow::mandateRef).containsExactly("MND-43");
    }

    /**
     * The grain defect (found by D5b). The delta asks a per-MANDATE question, so reading it at
     * INSTRUCTION grain lets ONE mandate answer twice and contradict itself: an accepted CREATE
     * and a rejected AMEND yield (MND, ACCP) AND (MND, RJCT) in the SAME window. The DISTINCT
     * that was there does not help: it only collapses rows that AGREE. The client is told its
     * mandate is both active and rejected, and {@code upsertWatermark} (keyed
     * (client, mandate_ref), one last_state column) fires twice with a non-deterministic
     * survivor that can flap the next window. The delivery ledger's (client, mandate_ref, state)
     * key treats both rows as legitimate, so no zero-duplicate audit catches it.
     */
    @Test
    void anAcceptedCreateAndARejectedAmendYieldOneDeltaRowNotTwoContradictoryOnes() {
        seedSpine("CLD05", "MND-44", "MREQ-44A");
        seedPbsr("MREQ-44A", "ACCP", null);
        seedInstruction("CLD05", "MND-44", "MREQ-44B", "AMEND");
        seedPbsr("MREQ-44B", "RJCT", "MD01");

        // the two INSTRUCTIONS legitimately disagree: that is the correct instruction-grain answer
        assertThat(stateOf("MREQ-44A")).isEqualTo("ACCP");
        assertThat(stateOf("MREQ-44B")).isEqualTo("RJCT");

        // the MANDATE has exactly one state, and a rejected AMEND does not un-register it
        assertThat(watermarks.findDeltaSlice("CLD05", "", SLICE))
                .containsExactly(new ManStateRow("MND-44", "ACCP"));
    }

    /** The resend path has the same grain: one row per mandate, carrying the MANDATE's state. */
    @Test
    void aResendRangeSliceIsAlsoOneRowPerMandate() {
        seedSpine("CLD06", "MND-45", "MREQ-45A");
        seedPbsr("MREQ-45A", "ACCP", null);
        seedInstruction("CLD06", "MND-45", "MREQ-45B", "CANCEL");
        seedPbsr("MREQ-45B", "ACCP", null);

        assertThat(watermarks.findRangeSlice("CLD06", "", SLICE))
                .containsExactly(new ManStateRow("MND-45", "CANC"));
    }

    /** Keyset paging is unchanged by the re-point: afterRef stays exclusive, order stays mandate_ref. */
    @Test
    void theKeysetSliceResumesStrictlyAfterTheGivenRef() {
        seedSpine("CLD07", "MND-46", "MREQ-46");
        seedPbsr("MREQ-46", "ACCP", null);
        seedSpine("CLD07", "MND-47", "MREQ-47");
        seedPbsr("MREQ-47", "ACCP", null);

        assertThat(watermarks.findDeltaSlice("CLD07", "", 1))
                .extracting(ManStateRow::mandateRef).containsExactly("MND-46");
        assertThat(watermarks.findDeltaSlice("CLD07", "MND-46", SLICE))
                .extracting(ManStateRow::mandateRef).containsExactly("MND-47");
        assertThat(watermarks.findRangeSlice("CLD07", "MND-46", SLICE))
                .extracting(ManStateRow::mandateRef).containsExactly("MND-47");
    }

    @Test
    void theRetiredProjectionViewIsGone() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.views WHERE table_name = 'man_ext_status'",
                Integer.class)).isZero();
    }

    private List<String> deltaRefs(final String client) {
        return watermarks.findDeltaSlice(client, "", SLICE).stream().map(ManStateRow::mandateRef).toList();
    }

    private void seedWatermark(final String client, final String mandateRef, final String state) {
        jdbc.update("INSERT INTO man_watermark (client, mandate_ref, last_state) VALUES (?,?,?)",
                client, mandateRef, state);
    }
}
