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
     * <p>DISTINCT because the row source is the request spine, which carries one entry per
     * mandate ACTION: a mandate with a CREATE and a later AMEND has two spine rows.</p>
     */
    @Query("SELECT DISTINCT mandate_ref FROM mandate_effective_status"
            + " WHERE state = 'ACCP' ORDER BY mandate_ref")
    List<String> findActiveRefs();
}
