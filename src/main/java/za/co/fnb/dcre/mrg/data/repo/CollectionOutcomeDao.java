package za.co.fnb.dcre.mrg.data.repo;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * READ-ONLY reader of the collections database (dcre_col) over MRG's SECOND datasource
 * (never the primary dcre_man datasource; see {@code ColDatasourceConfig}). The suspension
 * sweep asks for the most recent collection outcomes per mandate_ref so it can detect a
 * consecutive terminal-failed streak. MRG only ever READS dcre_col; it writes ONLY dcre_man.
 *
 * <p>Ported verbatim from MSR (SCRUM-91): the sweep moved services, the read contract did
 * not. Not a Spring Data repository like its siblings on purpose, because it must run on
 * the second datasource, and a {@code CrudRepository} would be bound to the primary one.</p>
 *
 * <p><b>Read contract (A-70, M10):</b> the CRG-owned {@code man_collection_outcome} view in
 * dcre_col, one row per collection line keyed by {@code mandate_ref}, with
 * {@code is_terminal_failure} = the collection's terminal status classified as
 * {@code TERMINAL_NON_SUCCESS} (RJCT / CANC), ordered by {@code occurred_at}. "Consecutive
 * terminal-failed" = the most recent {@code limit} outcomes are ALL terminal failures.</p>
 */
public class CollectionOutcomeDao {

    private final JdbcTemplate colJdbc;

    public CollectionOutcomeDao(final JdbcTemplate colJdbc) {
        this.colJdbc = colJdbc;
    }

    /** The most recent {@code limit} terminal-failure flags for the mandate, newest first. */
    public List<Boolean> recentTerminalFailures(final String mandateRef, final int limit) {
        return colJdbc.queryForList(
                "SELECT is_terminal_failure FROM man_collection_outcome WHERE mandate_ref = ? "
                        + "ORDER BY occurred_at DESC LIMIT ?",
                Boolean.class, mandateRef, limit);
    }
}
