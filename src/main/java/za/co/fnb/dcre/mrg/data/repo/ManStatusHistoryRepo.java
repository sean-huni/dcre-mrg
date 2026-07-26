package za.co.fnb.dcre.mrg.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mrg.data.model.ManStateRow;

import java.util.UUID;

/**
 * Append-only mandate transition audit trail, re-homed from MSR (SCRUM-91). MSR wrote a row
 * per response-leg EVENT; under derivation there is no event, so the transition is the DELTA
 * this report window observed: from the client's last reported state (man_watermark) to the
 * state the window is reporting.
 *
 * <p>The statement reads the watermark ITSELF rather than taking a from_state parameter, so
 * the row can never disagree with the watermark it is derived from: the caller
 * ({@link za.co.fnb.dcre.mrg.service.ManAdvanceRecorder}) must append BEFORE it advances the
 * watermark, which is the one ordering invariant this trail depends on. No watermark row at
 * all means this mandate has never been reported, and the from_state is the literal
 * REQUESTED, MSR's documented implicit start state (MandateState javadoc). The aggregate
 * subselect is what guarantees exactly one source row: {@code max()} with no GROUP BY yields
 * one row even when the mandate has no watermark.</p>
 *
 * <p>reason_code is the derived reason of the same collapse the state came from; it is a
 * scalar subselect, not a join, so a mandate that is no longer derivable (a ledgered line
 * whose spine has gone) still records its transition with a NULL reason instead of silently
 * writing nothing. source_leg and response_file have no meaning under derivation and are
 * left NULL; the columns survive only to carry MSR's historic evidence.</p>
 *
 * <p>Full-identity idempotency key UNIQUE(mandate_ref, to_state, report_id), mirroring
 * man_delivery_ledger's (client, mandate_ref, state): replaying the same report window is a
 * zero-duplicate no-op via ON CONFLICT DO NOTHING. NEVER UPSERT on the PK, CockroachDB
 * resolves UPSERT on the PK only. Query-only repository anchored on the {@link ManStateRow}
 * projection, the same shape as the other MRG repositories.</p>
 */
public interface ManStatusHistoryRepo extends Repository<ManStateRow, UUID> {

    /** @return rows written: 1 on a genuine transition, 0 on a replay or a non-transition. */
    @Modifying
    @Query("""
            INSERT INTO mandate_status_history
                (id, mandate_ref, from_state, to_state, reason_code, report_id)
            SELECT gen_random_uuid(), :mandateRef, w.from_state, :toState,
                   (SELECT s.reason FROM mandate_current_status s
                    WHERE s.client = :client AND s.mandate_ref = :mandateRef),
                   :reportId
            FROM (SELECT COALESCE(max(m.last_state), 'REQUESTED') AS from_state
                  FROM man_watermark m
                  WHERE m.client = :client AND m.mandate_ref = :mandateRef) w
            WHERE w.from_state <> :toState
            ON CONFLICT (mandate_ref, to_state, report_id) DO NOTHING""")
    int appendIfAbsent(@Param("reportId") UUID reportId, @Param("client") String client,
                       @Param("mandateRef") String mandateRef, @Param("toState") String toState);
}
