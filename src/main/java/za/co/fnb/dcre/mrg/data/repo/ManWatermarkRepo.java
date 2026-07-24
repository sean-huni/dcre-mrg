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
 * its delta reads over the man_ext_status projection view. Mirrors prg's
 * PrgWatermarkRepo: the delta is a bounded keyset slice (resume after the
 * caller's last mandate_ref, "" for the first slice) so a growing projection
 * never materialises whole in heap.
 */
public interface ManWatermarkRepo extends Repository<ManStateRow, UUID> {

    /**
     * Bounded keyset slice of the delta: mandates whose projection state moved
     * past the client's last reported state (or were never reported). state is
     * projection-NOT-NULL; the guard mirrors prg for parity.
     */
    @Query(value = """
            SELECT x.mandate_ref, x.state
            FROM man_ext_status x
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
            SELECT x.mandate_ref, x.state
            FROM man_ext_status x
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
