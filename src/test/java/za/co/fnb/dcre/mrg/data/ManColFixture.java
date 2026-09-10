package za.co.fnb.dcre.mrg.data;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * A faithful double of the CRG-owned {@code man_collection_outcome} view in dcre_col
 * (A-70), the ONE cross-database signal the mandate view stack cannot derive.
 *
 * <p>Ported from msr's MsrTestTables so the suspension sweep is proved against the same
 * read contract it had before MRG absorbed it: the
 * {@code (mandate_ref, e2e, status, is_terminal_failure, occurred_at)} shape, the
 * classification-driven {@code is_terminal_failure} expression, and the NULL-mandate_ref
 * exclusion, over a tiny backing table rather than CRG's whole projection graph (that
 * join is proven crg-side). Idempotent, so a suite can call it per test.</p>
 */
public final class ManColFixture {

    private ManColFixture() {
    }

    public static void createManCollectionOutcome(final JdbcTemplate colJdbc) {
        colJdbc.execute("""
                CREATE TABLE IF NOT EXISTS prg_status_class (
                    code VARCHAR(8) NOT NULL PRIMARY KEY,
                    classification VARCHAR(32) NOT NULL)""");
        colJdbc.execute("""
                CREATE TABLE IF NOT EXISTS collection_outcome_src (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    mandate_ref VARCHAR(35),
                    e2e VARCHAR(35),
                    status VARCHAR(16) NOT NULL,
                    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now())""");
        colJdbc.execute("""
                CREATE OR REPLACE VIEW man_collection_outcome AS
                SELECT s.mandate_ref, s.e2e, s.status,
                       COALESCE(sc.classification = 'TERMINAL_NON_SUCCESS', false) AS is_terminal_failure,
                       s.occurred_at
                FROM collection_outcome_src s
                LEFT JOIN prg_status_class sc ON sc.code = s.status
                WHERE s.mandate_ref IS NOT NULL""");
        // Classification per crg 004-status-classification.xml: RJCT/CANC are the terminal
        // non-success codes; ACSC/ACCC are terminal success.
        colJdbc.update("INSERT INTO prg_status_class (code, classification) VALUES "
                + "('RJCT','TERMINAL_NON_SUCCESS'), ('CANC','TERMINAL_NON_SUCCESS'), "
                + "('ACSC','TERMINAL_SUCCESS'), ('ACCC','TERMINAL_SUCCESS') "
                + "ON CONFLICT (code) DO NOTHING");
    }

    public static void insertCollectionOutcome(final JdbcTemplate colJdbc, final String mandateRef,
                                               final String status, final Instant occurredAt) {
        colJdbc.update("INSERT INTO collection_outcome_src (mandate_ref, e2e, status, occurred_at)"
                        + " VALUES (?,?,?,?)",
                mandateRef, "E2E-" + mandateRef + "-" + occurredAt.toEpochMilli(), status,
                Timestamp.from(occurredAt));
    }
}
