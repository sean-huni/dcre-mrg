package za.co.fnb.dcre.mrg.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mrg.data.model.ManStateRow;

import java.util.List;
import java.util.UUID;

/**
 * Per-mandate delta watermark (last externally reported state per mandate) and
 * its delta reads over the DERIVED mandate_effective_status view (SCRUM-91; the
 * retired man_ext_status was projection-dressing over the MSR-written mandate
 * table). Mirrors prg's PrgWatermarkRepo: the delta is a bounded keyset slice
 * (resume after the caller's last mandate_ref, "" for the first slice) so a
 * growing mandate population never materialises whole in heap.
 *
 * <p>Width contract: {@code state} is either a CASE literal (longest EXPIRED, 7)
 * or an explicit {@code mandate_override.state}, VARCHAR(16). It therefore fits
 * man_watermark.last_state and man_delivery_ledger.state, both VARCHAR(16), with
 * nothing to spare. The view's 32-wide {@code raw_status} (it COALESCEs
 * man_validation_log.outcome, VARCHAR(32)) is NEVER selected here: only the FSM
 * output crosses into a persisted column. Widening mandate_override.state or
 * adding a longer CASE literal means widening those two columns first.</p>
 */
public interface ManWatermarkRepo extends Repository<ManStateRow, UUID> {

    /**
     * Bounded keyset slice of the delta: mandates whose derived state moved past
     * the client's last reported state (or were never reported). state is
     * view-NOT-NULL (the FSM CASE has an ELSE arm); the guard mirrors prg for
     * parity. DISTINCT preserves the retired view's collapsing: the row source is
     * the request spine, which carries one entry per mandate ACTION, so a mandate
     * with a CREATE and a later CANCEL entry has two spine rows.
     */
    @Query(value = """
            SELECT DISTINCT x.mandate_ref, x.state
            FROM mandate_effective_status x
            LEFT JOIN man_watermark w ON w.client = x.client AND w.mandate_ref = x.mandate_ref
            WHERE x.client = :client AND x.state IS NOT NULL
              AND (w.mandate_ref IS NULL OR w.last_state <> x.state)
              AND x.mandate_ref > :afterRef
            ORDER BY x.mandate_ref
            LIMIT :limit""", rowMapperClass = ManStateRowMapper.class)
    List<ManStateRow> findDeltaSlice(@Param("client") String client, @Param("afterRef") String afterRef,
                                     @Param("limit") int limit);

    /** Resend override, same keyset slicing: all current mandate states, watermark ignored. */
    @Query(value = """
            SELECT DISTINCT x.mandate_ref, x.state
            FROM mandate_effective_status x
            WHERE x.client = :client AND x.state IS NOT NULL
              AND x.mandate_ref > :afterRef
            ORDER BY x.mandate_ref
            LIMIT :limit""", rowMapperClass = ManStateRowMapper.class)
    List<ManStateRow> findRangeSlice(@Param("client") String client, @Param("afterRef") String afterRef,
                                     @Param("limit") int limit);

    /** NEVER UPSERT INTO: CRDB resolves UPSERT on PK only; business identity is (client, mandate_ref). */
    @Modifying
    @Query("""
            INSERT INTO man_watermark (id, client, mandate_ref, last_state)
            VALUES (gen_random_uuid(), :client, :mandateRef, :state)
            ON CONFLICT (client, mandate_ref)
            DO UPDATE SET last_state = excluded.last_state, updated_at = now()""")
    void upsertWatermark(@Param("client") String client, @Param("mandateRef") String mandateRef,
                         @Param("state") String state);
}
