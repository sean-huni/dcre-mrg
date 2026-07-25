package za.co.fnb.dcre.mrg.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-91: the mandate FSM as a view expression. The non-obvious rule is preserved
 * verbatim from MSR's MandateStateMachine: ONLY a PBSR ACCP yields ACCP. An ISR or SBSR
 * ACCP means the instruction passed that stage but the debtor has not authenticated, so
 * the mandate stays PDNG. Expiry is a PREDICATE, never a written state (R-09: zero
 * clock-driven writers). Explicit mandate_override records beat the derived value.
 */
class MandateEffectiveStatusIT extends AbstractMrgCrdbIT {

    @Test
    void isrAccpDoesNotActivateTheMandate() {
        seedSpine("CL01", "MND-10", "MREQ-10");
        seedIsr("MREQ-10", "ACCP", null);

        assertThat(stateOf("MREQ-10")).isEqualTo("PDNG");
    }

    @Test
    void sbsrAccpDoesNotActivateTheMandate() {
        seedSpine("CL01", "MND-11", "MREQ-11");
        seedIsr("MREQ-11", "ACCP", null);
        seedSbsr("MREQ-11", "ACCP", null);

        assertThat(stateOf("MREQ-11")).isEqualTo("PDNG");
    }

    @Test
    void onlyPbsrAccpActivatesTheMandate() {
        seedSpine("CL01", "MND-12", "MREQ-12");
        seedPbsr("MREQ-12", "ACCP", null);

        assertThat(stateOf("MREQ-12")).isEqualTo("ACCP");
    }

    @Test
    void pbsrRejectIsTerminalAndCarriesItsReason() {
        seedSpine("CL01", "MND-13", "MREQ-13");
        seedPbsr("MREQ-13", "RJCT", "AC04");

        assertThat(stateOf("MREQ-13")).isEqualTo("RJCT");
        assertThat(reasonOf("MREQ-13")).isEqualTo("AC04");
        assertThat(noRetryOf("MREQ-13")).isTrue();
    }

    @Test
    void pbsrCancelIsTerminal() {
        seedSpine("CL01", "MND-14", "MREQ-14");
        seedPbsr("MREQ-14", "CANC", "MD07");

        assertThat(stateOf("MREQ-14")).isEqualTo("CANC");
        assertThat(reasonOf("MREQ-14")).isEqualTo("MD07");
    }

    @Test
    void anActiveMandatePastItsExpiryDateReadsExpiredWithNoWriterHavingRun() {
        seedSpineWithDates("CL01", "MND-15", "MREQ-15", "20260101", "20260701");
        seedPbsr("MREQ-15", "ACCP", null);

        assertThat(stateOf("MREQ-15")).isEqualTo("EXPIRED");
        // Scoped to this mandate: the suite shares one container, so a global count would
        // read another test's override row and prove nothing about THIS transition.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mandate_override"
                + " WHERE mandate_ref = 'MND-15'", Integer.class)).isZero();
    }

    @Test
    void aFutureExpiryDateStaysActive() {
        seedSpineWithDates("CL01", "MND-16", "MREQ-16", "20260101", "20991231");
        seedPbsr("MREQ-16", "ACCP", null);

        assertThat(stateOf("MREQ-16")).isEqualTo("ACCP");
    }

    @Test
    void anEmptyExpiryDateNeverExpires() {
        seedSpineWithDates("CL01", "MND-17", "MREQ-17", "20260101", "");
        seedPbsr("MREQ-17", "ACCP", null);

        assertThat(stateOf("MREQ-17")).isEqualTo("ACCP");
    }

    @Test
    void anOverrideBeatsTheDerivedState() {
        seedSpine("CL01", "MND-18", "MREQ-18");
        seedPbsr("MREQ-18", "ACCP", null);
        jdbc.update("INSERT INTO mandate_override (mandate_ref, state, reason, source) "
                + "VALUES (?, 'SUSPENDED', 'MS03', 'COLLECTION_FAILURE')", "MND-18");

        assertThat(stateOf("MREQ-18")).isEqualTo("SUSPENDED");
        assertThat(reasonOf("MREQ-18")).isEqualTo("MS03");
    }

    @Test
    void removingTheOverrideRestoresTheDerivedState() {
        seedSpine("CL01", "MND-19", "MREQ-19");
        seedPbsr("MREQ-19", "ACCP", null);
        jdbc.update("INSERT INTO mandate_override (mandate_ref, state, reason, source) "
                + "VALUES (?, 'SUSPENDED', 'MS03', 'COLLECTION_FAILURE')", "MND-19");
        jdbc.update("DELETE FROM mandate_override WHERE mandate_ref = ?", "MND-19");

        assertThat(stateOf("MREQ-19")).isEqualTo("ACCP");
    }

    /**
     * The request-leg rejection arm. An MRV FAIL_DUPLICATE_REF entry has a NULL mndt_req_id
     * (MRR B1a) and can NEVER receive a leg response, so it must read terminal here or the
     * 1:1-live admission check would treat it as a live twin and block a legitimate
     * re-registration of the same contract forever.
     */
    @Test
    void anMrvRejectedDuplicateIsTerminalNotLive() {
        final var arrival = seedSpine("CL01", "MND-20", "MREQ-20");
        seedDupEntry(arrival, 2, "MND-20");
        seedVerdict(arrival, 2, "FAIL_DUPLICATE_REF", "duplicate (mandate_ref, action_code) in file");

        assertThat(unassignedStateOf("MND-20")).isEqualTo("RJCT");
    }

    /** An MRV PASS is not a leg reply: the mandate is admitted but still awaiting Fintegrate. */
    @Test
    void anMrvPassAwaitingRepliesStaysPending() {
        final var arrival = seedSpine("CL01", "MND-21", "MREQ-21");
        seedVerdict(arrival, 1, "PASS", null);

        assertThat(stateOf("MREQ-21")).isEqualTo("PDNG");
    }

    @Test
    void ctvViewKeepsItsExactColumnContract() {
        final List<String> cols = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'man_ctv_view' "
                        + "ORDER BY ordinal_position", String.class);

        assertThat(cols).containsExactly("mandate_ref", "contract_ref", "creditor_account",
                "state", "start_date", "expiry_date", "max_collection_amount");
    }

    private String stateOf(final String mndtReqId) {
        return jdbc.queryForObject("SELECT state FROM mandate_effective_status"
                + " WHERE mndt_req_id = ?", String.class, mndtReqId);
    }

    /** State of a spine entry that was never assigned a mandate request id (MRR B1a loser). */
    private String unassignedStateOf(final String mandateRef) {
        return jdbc.queryForObject("SELECT state FROM mandate_effective_status"
                + " WHERE mandate_ref = ? AND mndt_req_id IS NULL", String.class, mandateRef);
    }

    private String reasonOf(final String mndtReqId) {
        return jdbc.queryForObject("SELECT reason FROM mandate_effective_status"
                + " WHERE mndt_req_id = ?", String.class, mndtReqId);
    }

    private Boolean noRetryOf(final String mndtReqId) {
        return jdbc.queryForObject("SELECT no_retry FROM mandate_effective_status"
                + " WHERE mndt_req_id = ?", Boolean.class, mndtReqId);
    }
}
