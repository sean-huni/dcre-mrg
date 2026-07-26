package za.co.fnb.dcre.mrg.data.repo;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import za.co.fnb.dcre.mrg.data.model.ManStateRow;

import java.util.List;
import java.util.UUID;

/**
 * Read side of the derived mandate state (SCRUM-91), for callers that need the population
 * rather than a per-client delta slice. Replaces MSR's {@code MandateProjectionDao}
 * candidate enumeration, which read the deleted mandate table.
 */
public interface ManEffectiveStatusRepo extends Repository<ManStateRow, UUID> {

    /**
     * Suspension-sweep candidates: every EFFECTIVELY active mandate. "Effectively" is
     * load-bearing. The state read here already has any explicit mandate_override applied
     * (R-09: explicit records beat the derived value), so a mandate an operator has pinned
     * is not a candidate, exactly as MSR's guarded {@code WHERE state = 'ACCP'} behaved
     * against the projection it wrote.
     *
     * <p>GRAIN (defect fixed 2026-07-25, found by D5b): this is a per-MANDATE question, so it
     * reads mandate_current_status (007, exactly one row per mandate_ref), NOT the per-
     * INSTRUCTION mandate_effective_status. The spine carries one entry per mandate ACTION, so
     * at instruction grain a mandate whose CREATE was accepted answered ACCP for ever: an
     * accepted CANCEL, an MD07 termination or an expiry arriving on a LATER instruction leaves
     * the CREATE row untouched. DISTINCT did not help, because those rows do not agree. The
     * sweep then wrote a SUSPENDED override onto a cancelled or terminated mandate, and since
     * an override outranks everything derived (R-09) that resurrected a dead mandate into
     * SUSPENDED. Reading the collapse also keeps the flip side right: a rejected AMEND rejects
     * the INSTRUCTION, and its mandate is still live and still a candidate.</p>
     */
    @Query("SELECT mandate_ref FROM mandate_current_status"
            + " WHERE state = 'ACCP' ORDER BY mandate_ref")
    List<String> findActiveRefs();
}
