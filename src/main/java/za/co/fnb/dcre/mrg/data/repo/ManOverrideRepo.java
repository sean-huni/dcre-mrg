package za.co.fnb.dcre.mrg.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mrg.data.model.ManStateRow;

import java.util.UUID;

/**
 * The ONE writer in the mandate response leg (SCRUM-91). Everything else about a mandate's
 * state is derived: per INSTRUCTION by mandate_effective_status, per MANDATE by the
 * mandate_current_status collapse. This sink exists only for signals a CockroachDB view
 * cannot reach, today just suspension, whose evidence lives in dcre_col.
 *
 * <p>The write is guarded on the FULL business identity (mandate_ref, source): never UPSERT
 * on the PK, because CRDB resolves UPSERT on the PK only. Keying on mandate_ref alone would
 * let an OPS override and a COLLECTION_FAILURE override clobber each other, the subset-key
 * defect the idempotency rule forbids. The DO UPDATE carries a WHERE so re-writing an
 * override that already stands writes NO row: a resumed sweep is a zero-duplicate no-op and
 * does not churn updated_at.</p>
 *
 * <p>Reads of this table go through mandate_override_pick, never the table itself: one
 * mandate can hold several sources at once and the join would fan out (see
 * 005-man-effective.xml). Query-only repository anchored on the {@link ManStateRow}
 * projection, the same shape as the other MRG repositories.</p>
 */
public interface ManOverrideRepo extends Repository<ManStateRow, UUID> {

    /** @return rows written: 1 on a new or genuinely changed override, 0 when it already stands. */
    @Modifying
    @Query("""
            INSERT INTO mandate_override (id, mandate_ref, state, reason, source)
            VALUES (gen_random_uuid(), :mandateRef, :state, :reason, :source)
            ON CONFLICT (mandate_ref, source)
            DO UPDATE SET state = excluded.state, reason = excluded.reason, updated_at = now()
            WHERE mandate_override.state <> excluded.state
               OR mandate_override.reason IS DISTINCT FROM excluded.reason""")
    int upsertGuarded(@Param("mandateRef") String mandateRef, @Param("state") String state,
                      @Param("reason") String reason, @Param("source") String source);
}
