package za.co.fnb.dcre.mrg.data;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * MRR request-spine seeds, shared by every MRG suite (SCRUM-91). The spine is the row
 * source of the whole derived mandate view stack, so once MRG reads
 * mandate_effective_status instead of the MSR-written mandate table, EVERY suite has to
 * seed it. Factored out here rather than copied a fourth time.
 *
 * <p>Static and JdbcTemplate-taking on purpose: the callers are four unrelated
 * Testcontainers bases (a shared abstract IT, two standalone @SpringBootTests and the
 * cucumber glue), so there is no common superclass to hang instance helpers on.</p>
 */
public final class ManSpineFixture {

    private ManSpineFixture() {
    }

    /** One header + entry with a far-future expiry, the plain "nothing special" mandate. */
    public static UUID seedSpine(final JdbcTemplate jdbc, final String client,
                                 final String mandateRef, final String mndtReqId) {
        return seedSpineWithDates(jdbc, client, mandateRef, mndtReqId, "20260101", "20991231");
    }

    /** Same seed with explicit CCYYMMDD start/expiry: the spine stores VARCHAR(8), never DATE. */
    public static UUID seedSpineWithDates(final JdbcTemplate jdbc, final String client,
                                          final String mandateRef, final String mndtReqId,
                                          final String startDate, final String expiryDate) {
        return seedInstruction(jdbc, client, mandateRef, mndtReqId, "CREATE", startDate, expiryDate);
    }

    /**
     * A FOLLOW-UP instruction on an existing mandate_ref (D5b). The spine has NO uniqueness on
     * mandate_ref: it is keyed (arrival_id, sequence), so one mandate accumulates a CREATE, then
     * AMENDs, then a CANCEL, each its own entry in its own file with its own mndt_req_id and its
     * own leg replies. That is the fan-out mandate_current_status collapses, and no suite could
     * express it before this helper existed.
     */
    public static UUID seedInstruction(final JdbcTemplate jdbc, final String client,
                                       final String mandateRef, final String mndtReqId,
                                       final String actionCode) {
        return seedInstruction(jdbc, client, mandateRef, mndtReqId, actionCode, "20260101", "20991231");
    }

    /** Same follow-up instruction with explicit CCYYMMDD dates: an AMEND that moves the expiry. */
    public static UUID seedInstruction(final JdbcTemplate jdbc, final String client,
                                       final String mandateRef, final String mndtReqId,
                                       final String actionCode, final String startDate,
                                       final String expiryDate) {
        final UUID arrival = UUID.randomUUID();
        jdbc.update("INSERT INTO mandate_request_header (arrival_id, msg_id_raw, msg_id, created_ts,"
                        + " entry_count, destination_id, business_date, client_token, layout_version)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                arrival, "MSG" + mndtReqId, "MSG" + mndtReqId, "20260725080000", 1, "ONHOST",
                "20260725", client, 1);
        jdbc.update("INSERT INTO mandate_request_entry (arrival_id, sequence, record_type, action_code,"
                        + " mandate_ref, contract_ref, creditor_account, debtor_account, currency,"
                        + " max_collection_amount_raw, max_collection_amount, start_date, expiry_date,"
                        + " mndt_req_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrival, 1, "MD", actionCode, mandateRef, "CTR" + mandateRef, "62000000010",
                "62000000020", "ZAR", "1000", 10.00, startDate, expiryDate, mndtReqId);
        return arrival;
    }

    /**
     * MRR B1a: the LATER occurrence of an intra-file (mandate_ref, action_code) duplicate is
     * landed with mndt_req_id NULL and dup_in_file true. It never reaches Fintegrate, so it can
     * NEVER receive a leg response; only MRV's verdict can ever give it a status.
     */
    public static void seedDupEntry(final JdbcTemplate jdbc, final UUID arrival, final int sequence,
                                    final String mandateRef) {
        jdbc.update("INSERT INTO mandate_request_entry (arrival_id, sequence, record_type, action_code,"
                        + " mandate_ref, contract_ref, creditor_account, debtor_account, currency,"
                        + " max_collection_amount_raw, max_collection_amount, start_date, expiry_date,"
                        + " mndt_req_id, dup_in_file) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,true)",
                arrival, sequence, "MD", "CREATE", mandateRef, "CTR" + mandateRef, "62000000010",
                "62000000020", "ZAR", "1000", 10.00, "20260101", "20991231");
    }

    /** MRV's request-leg verdict sink (man_validation_log), keyed on (arrival_id, sequence). */
    public static void seedVerdict(final JdbcTemplate jdbc, final UUID arrival, final int sequence,
                                   final String outcome, final String detail) {
        jdbc.update("INSERT INTO man_validation_log (arrival_id, sequence, outcome, detail)"
                + " VALUES (?,?,?,?)", arrival, sequence, outcome, detail);
    }

    /**
     * An explicit override at a pinned effective_from. The unique key is the FULL business
     * identity (mandate_ref, source), so one mandate can legitimately carry several of these
     * at once; mandate_override_pick is what collapses them to the single winner.
     */
    public static void seedOverride(final JdbcTemplate jdbc, final String mandateRef,
                                    final String state, final String reason, final String source,
                                    final String effectiveFrom) {
        jdbc.update("INSERT INTO mandate_override (mandate_ref, state, reason, source, effective_from)"
                + " VALUES (?,?,?,?,?::TIMESTAMPTZ)", mandateRef, state, reason, source, effectiveFrom);
    }
}
