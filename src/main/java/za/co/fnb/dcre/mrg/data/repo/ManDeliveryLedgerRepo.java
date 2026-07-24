package za.co.fnb.dcre.mrg.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mrg.data.model.ManStateRow;

import java.util.List;
import java.util.UUID;

/**
 * Append-only delivery ledger: the authority on what mandate state was
 * externally reported. Auto sends are arbitrated by the DATABASE via the
 * partial unique index uq_man_ledger_auto ON (client, mandate_ref, state)
 * WHERE manual_ref IS NULL: never auto-report the same mandate state twice,
 * across restarts and racing windows alike. Manual rows (manual_ref set) fall
 * outside the partial index, so overrides bypass the guard while still writing
 * an audit row. Query-only repository anchored on the {@link ManStateRow} projection.
 */
public interface ManDeliveryLedgerRepo extends Repository<ManStateRow, UUID> {

    /** Auto-guarded append: a colliding auto row is a silent no-op (guard semantics above). */
    @Modifying
    @Query("""
            INSERT INTO man_delivery_ledger (id, report_id, client, mandate_ref, state, manual_ref)
            VALUES (gen_random_uuid(), :reportId, :client, :mandateRef, :state, :manualRef)
            ON CONFLICT (client, mandate_ref, state) WHERE manual_ref IS NULL DO NOTHING""")
    void record(@Param("reportId") UUID reportId, @Param("client") String client,
                @Param("mandateRef") String mandateRef, @Param("state") String state,
                @Param("manualRef") String manualRef);

    @Query("SELECT count(*) FROM man_delivery_ledger WHERE report_id = :reportId")
    long countForReport(@Param("reportId") UUID reportId);

    /** Ledgered lines of one report in emission order: the exact-replay source. */
    @Query(value = "SELECT mandate_ref, state FROM man_delivery_ledger WHERE report_id = :reportId"
            + " ORDER BY mandate_ref", rowMapperClass = ManStateRowMapper.class)
    List<ManStateRow> rowsForReport(@Param("reportId") UUID reportId);
}
