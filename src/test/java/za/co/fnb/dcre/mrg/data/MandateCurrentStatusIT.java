package za.co.fnb.dcre.mrg.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-91 D5b: the per-mandate collapse. mandate_request_entry has NO uniqueness on
 * mandate_ref (it is keyed (arrival_id, sequence)), so one mandate accumulates a CREATE,
 * then AMENDs, then a CANCEL, each its own entry with its own leg replies. mnd_ext_status
 * and mandate_effective_status therefore yield N rows per mandate, which is the CORRECT
 * grain for reporting what happened to each INSTRUCTION but the wrong grain for CTV and
 * for the parity gate.
 *
 * <p>mandate_current_status is one row per mandate_ref, reformulating MSR's sequential
 * MandateStateMachine fold as order-independent aggregate predicates. Collapsing by
 * picking a single entry is wrong whichever entry you pick, and
 * {@link #anAcceptedCreateThenARejectedAmendReadsAccpNotRjct} is the case that proves it:
 * a rejected AMEND is RJCT for that INSTRUCTION while the MANDATE stays ACCP.</p>
 */
class MandateCurrentStatusIT extends AbstractMrgCrdbIT {

    /**
     * The defect, stated as a test. Three instructions, one mandate: the instruction-grain
     * view legitimately shows three rows, and everything at mandate grain must show one.
     */
    @Test
    void aCreateAmendAndCancelForOneMandateCollapseToASingleRow() {
        seedSpine("CL01", "MND-30", "MREQ-30A");
        seedInstruction("CL01", "MND-30", "MREQ-30B", "AMEND");
        seedInstruction("CL01", "MND-30", "MREQ-30C", "CANCEL");
        seedPbsr("MREQ-30A", "ACCP", null);

        assertThat(rowCountOf("mandate_effective_status", "MND-30")).isEqualTo(3);
        assertThat(rowCountOf("man_ctv_view", "MND-30")).isEqualTo(1);
        assertThat(rowCountOf("mandate_current_status", "MND-30")).isEqualTo(1);
    }

    /**
     * THE test of this task. MSR's FSM was a sequential fold keyed on mandate_ref with
     * no-regression of an active mandate; a rejected AMEND rejects the INSTRUCTION, it does
     * not un-register the mandate. "Latest entry wins" would read RJCT here and stop the
     * client's collections on a mandate the debtor authorised.
     */
    @Test
    void anAcceptedCreateThenARejectedAmendReadsAccpNotRjct() {
        seedSpine("CL01", "MND-31", "MREQ-31A");
        seedPbsr("MREQ-31A", "ACCP", null);
        seedInstruction("CL01", "MND-31", "MREQ-31B", "AMEND");
        seedPbsr("MREQ-31B", "RJCT", "MD01");

        // the INSTRUCTION was rejected: that is what mandate_effective_status reports
        assertThat(stateOf("MREQ-31B")).isEqualTo("RJCT");
        // the MANDATE is still registered
        assertThat(currentStateOf("MND-31")).isEqualTo("ACCP");
    }

    /** A rejected CREATE means the mandate never existed: the action_code-scoped arm. */
    @Test
    void aRejectedCreateReadsRjct() {
        seedSpine("CL01", "MND-32", "MREQ-32");
        seedPbsr("MREQ-32", "RJCT", "AC04");

        assertThat(currentStateOf("MND-32")).isEqualTo("RJCT");
    }

    /** An accepted CANCEL freezes the mandate, so its presence anywhere wins over ACCP. */
    @Test
    void anAcceptedCancelReadsCancEvenAfterAnAcceptedCreate() {
        seedSpine("CL01", "MND-33", "MREQ-33A");
        seedPbsr("MREQ-33A", "ACCP", null);
        seedInstruction("CL01", "MND-33", "MREQ-33B", "CANCEL");
        seedPbsr("MREQ-33B", "ACCP", null);

        assertThat(currentStateOf("MND-33")).isEqualTo("CANC");
    }

    /**
     * D8: Fintegrate can cancel a mandate unilaterally, and it does so with pain.012
     * Sts=CANC on the leg of the instruction it is answering, which is the CREATE. There is
     * no CANCEL instruction anywhere in that story, so an arm keyed only on
     * action_code = 'CANCEL' misses it and the mandate falls through to PDNG.
     */
    @Test
    void aFintegrateCancellationOnTheCreateInstructionReadsCanc() {
        seedSpine("CL01", "MND-60", "MREQ-60");
        seedPbsr("MREQ-60", "CANC", "MD06");

        assertThat(currentStateOf("MND-60")).isEqualTo("CANC");
        assertThat(currentReasonOf("MND-60")).isEqualTo("MD06");
    }

    /** Expiry is a predicate, at mandate grain too: no writer has run. */
    @Test
    void anActivatedMandatePastItsExpiryReadsExpired() {
        seedSpineWithDates("CL01", "MND-61", "MREQ-61", "20260101", "20260701");
        seedPbsr("MREQ-61", "ACCP", null);

        assertThat(currentStateOf("MND-61")).isEqualTo("EXPIRED");
    }

    /**
     * The expiry arm must read the expiry this row PUBLISHES, not any superseded entry's.
     * An accepted AMEND that extends the expiry leaves an EXPIRED instruction row behind it;
     * bool_or(state = 'EXPIRED') would report the mandate expired while the same row
     * publishes an expiry_date years away, and CTV would refuse a live mandate.
     */
    @Test
    void anAmendThatExtendsTheExpiryKeepsTheMandateActive() {
        seedSpineWithDates("CL01", "MND-62", "MREQ-62A", "20260101", "20260701");
        seedPbsr("MREQ-62A", "ACCP", null);
        seedInstruction("CL01", "MND-62", "MREQ-62B", "AMEND", "20260101", "20991231");
        seedPbsr("MREQ-62B", "ACCP", null);

        assertThat(stateOf("MREQ-62A")).isEqualTo("EXPIRED");
        assertThat(currentStateOf("MND-62")).isEqualTo("ACCP");
        assertThat(queryOne("SELECT expiry_date FROM mandate_current_status"
                + " WHERE mandate_ref = 'MND-62'")).containsEntry("expiry_date", "20991231");
    }

    /**
     * MD07 (system_action TERMINATE_NOW) terminates from any non-terminal state, so its
     * presence on ANY instruction wins. Without this arm the mandate would read ACCP: the
     * carrier here is an AMEND, which the rejected-CREATE arm deliberately ignores.
     */
    @Test
    void aTerminateNowReasonOnAnyInstructionReadsRjct() {
        seedSpine("CL01", "MND-34", "MREQ-34A");
        seedPbsr("MREQ-34A", "ACCP", null);
        seedInstruction("CL01", "MND-34", "MREQ-34B", "AMEND");
        seedPbsr("MREQ-34B", "RJCT", "MD07");

        assertThat(currentStateOf("MND-34")).isEqualTo("RJCT");
        assertThat(currentReasonOf("MND-34")).isEqualTo("MD07");
    }

    /** R-09: an explicit record beats anything derived, at mandate grain too. */
    @Test
    void anOverrideStillBeatsTheDerivedState() {
        seedSpine("CL01", "MND-35", "MREQ-35A");
        seedPbsr("MREQ-35A", "ACCP", null);
        seedInstruction("CL01", "MND-35", "MREQ-35B", "AMEND");
        seedPbsr("MREQ-35B", "ACCP", null);
        seedOverride("MND-35", "SUSPENDED", "MS03", "COLLECTION_FAILURE", "2026-07-20T08:00:00Z");

        assertThat(currentStateOf("MND-35")).isEqualTo("SUSPENDED");
        assertThat(currentReasonOf("MND-35")).isEqualTo("MS03");
        assertThat(rowCountOf("mandate_current_status", "MND-35")).isEqualTo(1);
    }

    /**
     * MRR B1a: the loser of an intra-file duplicate lands with mndt_req_id NULL and can NEVER
     * receive a leg reply, so only MRV's verdict can give it a status. Its mandate has no other
     * entry, so the mandate itself must read terminal, or the 1:1-live check counts it as a live
     * twin and blocks a legitimate re-registration of that contract forever.
     */
    @Test
    void anMrvRejectedDuplicateAsTheOnlyEntryReadsRjctAndIsNotLive() {
        final var arrival = seedSpine("CL01", "MND-36", "MREQ-36");
        seedDupEntry(arrival, 2, "MND-37");
        seedVerdict(arrival, 2, "FAIL_DUPLICATE_REF", "duplicate (mandate_ref, action_code) in file");

        assertThat(currentStateOf("MND-37")).isEqualTo("RJCT");
        assertThat(rowCountOf("mandate_current_status", "MND-37")).isEqualTo(1);
    }

    /**
     * "Never regress an active mandate": once any instruction activated it, a later structural
     * leg or a pending row cannot pull it back to PDNG. The AMEND here has only an ISR ACCP, so
     * its own instruction-grain state is PDNG (only a PBSR ACCP activates).
     */
    @Test
    void anAccpMandateWithALaterPendingInstructionStaysAccp() {
        seedSpine("CL01", "MND-38", "MREQ-38A");
        seedPbsr("MREQ-38A", "ACCP", null);
        seedInstruction("CL01", "MND-38", "MREQ-38B", "AMEND");
        seedIsr("MREQ-38B", "ACCP", null);

        assertThat(stateOf("MREQ-38B")).isEqualTo("PDNG");
        assertThat(currentStateOf("MND-38")).isEqualTo("ACCP");
    }

    /** CTV's contract survives the re-point: same columns, same values, now one row per mandate. */
    @Test
    void ctvStillSeesOneRowCarryingTheMandateAttributes() {
        seedSpine("CL01", "MND-39", "MREQ-39A");
        seedPbsr("MREQ-39A", "ACCP", null);
        seedInstruction("CL01", "MND-39", "MREQ-39B", "AMEND");

        final var row = queryOne("SELECT * FROM man_ctv_view WHERE mandate_ref = 'MND-39'");

        assertThat(row).containsOnlyKeys("mandate_ref", "contract_ref", "creditor_account",
                "state", "start_date", "expiry_date", "max_collection_amount");
        assertThat(row.get("contract_ref")).isEqualTo("CTRMND-39");
        assertThat(row.get("creditor_account")).isEqualTo("62000000010");
        assertThat(row.get("state")).isEqualTo("ACCP");
        assertThat(row.get("expiry_date")).isEqualTo("20991231");
    }

    private String currentStateOf(final String mandateRef) {
        return jdbc.queryForObject("SELECT state FROM mandate_current_status"
                + " WHERE mandate_ref = ?", String.class, mandateRef);
    }

    private String currentReasonOf(final String mandateRef) {
        return jdbc.queryForObject("SELECT reason FROM mandate_current_status"
                + " WHERE mandate_ref = ?", String.class, mandateRef);
    }
}
