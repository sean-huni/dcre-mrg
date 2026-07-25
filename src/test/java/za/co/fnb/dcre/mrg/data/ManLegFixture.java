package za.co.fnb.dcre.mrg.data;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Response-leg seeds plus the derived-state shortcuts the report suites need (SCRUM-91).
 *
 * <p>Before this refactor a report test could write a mandate state directly
 * ({@code INSERT INTO mandate (..., state)}). There is no state to write any more: state
 * is DERIVED, so a test that wants a mandate reading ACCP must seed the reply that makes
 * it read ACCP. {@link #seedMandateInState} and {@link #advanceTo} are those two moves,
 * and they are deliberately the ONLY way the report suites reach a state, so a change to
 * the FSM cannot leave a suite asserting a state the view can no longer produce.</p>
 */
public final class ManLegFixture {

    private ManLegFixture() {
    }

    /** Derived-state shortcut: seeds the spine plus whatever reply makes the mandate read {@code state}. */
    public static void seedMandateInState(final JdbcTemplate jdbc, final String client,
                                          final String mandateRef, final String state) {
        ManSpineFixture.seedSpine(jdbc, client, mandateRef, reqIdOf(mandateRef));
        if (!"PDNG".equals(state)) {
            advanceTo(jdbc, mandateRef, state);
        }
    }

    /**
     * Moves an already-seeded mandate to {@code state} by landing a fresh PBSR reply.
     * PBSR is the only leg that can move a mandate off PDNG (the FSM's non-obvious rule:
     * an ISR or SBSR ACCP means the instruction passed that stage, not that the debtor
     * authenticated). The pick view takes the newest row, so this supersedes any earlier reply.
     */
    public static void advanceTo(final JdbcTemplate jdbc, final String mandateRef, final String state) {
        seedLeg(jdbc, "man_pbsr_resp", reqIdOf(mandateRef), state, null,
                "%s-%s_PBSR.xml".formatted(mandateRef, state), null);
    }

    /** Deterministic mandate request id for the shortcut seeds, so advanceTo can find the mandate. */
    public static String reqIdOf(final String mandateRef) {
        return mandateRef + "-Q";
    }

    public static void seedLeg(final JdbcTemplate jdbc, final String table, final String mndtReqId,
                               final String status, final String reason, final String responseFile,
                               final String createdAt) {
        final String columns = "(response_file, orgnl_msg_id, mndt_id, mndt_req_id, status, reason";
        final String mndtId = mandateRefOf(jdbc, mndtReqId);
        if (createdAt == null) {
            jdbc.update("INSERT INTO %s %s) VALUES (?,?,?,?,?,?)".formatted(table, columns),
                    responseFile, "OUT-" + mndtReqId, mndtId, mndtReqId, status, reason);
            return;
        }
        jdbc.update("INSERT INTO %s %s, created_at) VALUES (?,?,?,?,?,?,?::TIMESTAMPTZ)".formatted(table, columns),
                responseFile, "OUT-" + mndtReqId, mndtId, mndtReqId, status, reason, createdAt);
    }

    /** MndtId on a reply is the mandate_ref; resolve it from the spine so fixtures stay real. */
    private static String mandateRefOf(final JdbcTemplate jdbc, final String mndtReqId) {
        final List<String> refs = jdbc.queryForList(
                "SELECT mandate_ref FROM mandate_request_entry WHERE mndt_req_id = ?",
                String.class, mndtReqId);
        return refs.isEmpty() ? mndtReqId : refs.getFirst();
    }
}
