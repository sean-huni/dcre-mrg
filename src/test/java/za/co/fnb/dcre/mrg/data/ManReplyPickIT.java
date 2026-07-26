package za.co.fnb.dcre.mrg.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-91: the leg pick views must not depend on the order the readers COMMITTED.
 *
 * <p>created_at is stamped by the DEFAULT now() when a reader INSERTs, and the three leg
 * readers are independent concurrent Kubernetes Jobs, so that column records POD COMPLETION
 * order, not the order the bank sent the replies. The delayed debtor-authentication path
 * deliberately produces TWO PBSR files for one mandate ({@code <stem>_PBSR.xml}, usually PDNG,
 * then {@code <stem>-AUTH_PBSR.xml} carrying the decision), so a slow first pod lands the stale
 * PDNG last and it wins the pick: the mandate reads PDNG permanently and collections never
 * start for a live customer. Observed live, 1 of 5 delayed-auth mandates.</p>
 *
 * <p>The response_file DESC tiebreak makes a created_at tie worse rather than safe: it compares
 * {@code _PBSR.xml} against {@code -AUTH_PBSR.xml}, and '_' (0x5F) sorts above '-' (0x2D), so
 * the STALE non-auth file wins by construction.</p>
 */
class ManReplyPickIT extends AbstractMrgCrdbIT {

    /** The live defect: the pending reply commits AFTER the decision and must still lose. */
    @Test
    void aPendingReplyCommittedAfterTheDecisionStillReadsTheDecision() {
        seedSpine("CL01", "MND-80", "MREQ-80");
        seedPbsrAt("MREQ-80", "ACCP", null, "MREQ-80-AUTH_PBSR.xml", "2026-07-24T09:00:00Z");
        seedPbsrAt("MREQ-80", "PDNG", null, "MREQ-80_PBSR.xml", "2026-07-24T09:05:00Z");

        assertThat(pickStatusOf("mnd_pbsr_pick", "MREQ-80")).isEqualTo("ACCP");
        assertThat(stateOf("MREQ-80")).isEqualTo("ACCP");
    }

    /** The tiebreak: on identical created_at the byte order of the filenames must not decide. */
    @Test
    void aCreatedAtTieNeverLetsTheStaleNonAuthFileWin() {
        seedSpine("CL01", "MND-81", "MREQ-81");
        seedPbsrAt("MREQ-81", "PDNG", null, "MREQ-81_PBSR.xml", "2026-07-24T09:00:00Z");
        seedPbsrAt("MREQ-81", "ACCP", null, "MREQ-81-AUTH_PBSR.xml", "2026-07-24T09:00:00Z");

        assertThat(pickStatusOf("mnd_pbsr_pick", "MREQ-81")).isEqualTo("ACCP");
        assertThat(stateOf("MREQ-81")).isEqualTo("ACCP");
    }

    /** The leader must not invent a decision: a lone PDNG with no decided sibling stays PDNG. */
    @Test
    void aLonePendingReplyStillReadsPending() {
        seedSpine("CL01", "MND-82", "MREQ-82");
        seedPbsrAt("MREQ-82", "PDNG", null, "MREQ-82_PBSR.xml", "2026-07-24T09:00:00Z");

        assertThat(pickStatusOf("mnd_pbsr_pick", "MREQ-82")).isEqualTo("PDNG");
        assertThat(stateOf("MREQ-82")).isEqualTo("PDNG");
    }

    /** All three pick views share the flaw, so all three carry the guard. */
    @ParameterizedTest
    @CsvSource({"man_isr_resp, mnd_isr_pick, MND-83, MREQ-83",
                "man_sbsr_resp, mnd_sbsr_pick, MND-84, MREQ-84"})
    void aMisorderedCommitOnTheOtherLegsStillReadsTheDecision(final String table, final String view,
                                                              final String mandateRef,
                                                              final String reqId) {
        seedSpine("CL01", mandateRef, reqId);
        seedLegAt(table, reqId, "RJCT", "AC04", reqId + "-DECIDED.xml", "2026-07-24T09:00:00Z");
        seedLegAt(table, reqId, "PDNG", null, reqId + "_PENDING.xml", "2026-07-24T09:05:00Z");

        assertThat(pickStatusOf(view, reqId)).isEqualTo("RJCT");
        assertThat(stateOf(reqId)).isEqualTo("RJCT");
    }
}
