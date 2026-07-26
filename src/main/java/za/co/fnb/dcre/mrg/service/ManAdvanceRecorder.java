package za.co.fnb.dcre.mrg.service;

import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mrg.data.model.ManStateRow;
import za.co.fnb.dcre.mrg.data.repo.ManDeliveryLedgerRepo;
import za.co.fnb.dcre.mrg.data.repo.ManStatusHistoryRepo;
import za.co.fnb.dcre.mrg.data.repo.ManWatermarkRepo;

import java.util.UUID;

/**
 * The three writes one advanced mandate produces, kept together because they are ONE
 * observation: the transition audit row, the delivery ledger row and the watermark. Split out
 * of {@link ManDeliveryAdvancer} with the SCRUM-91 history append so the advancer keeps a
 * single responsibility (stream the emitted file, slice it, own the transaction and retry)
 * and this class keeps the other one (what a single advanced mandate durably records).
 *
 * <p>Every write is full-identity guarded, so the whole method is idempotent and a
 * kill-resume replay of the same report window changes nothing.</p>
 */
@Service
public class ManAdvanceRecorder {

    private final ManStatusHistoryRepo history;
    private final ManDeliveryLedgerRepo ledger;
    private final ManWatermarkRepo watermarks;

    public ManAdvanceRecorder(final ManStatusHistoryRepo history, final ManDeliveryLedgerRepo ledger,
                              final ManWatermarkRepo watermarks) {
        this.history = history;
        this.ledger = ledger;
        this.watermarks = watermarks;
    }

    /**
     * ORDER IS LOAD-BEARING: the history append derives its from_state by reading the
     * watermark, so it MUST run before the watermark is advanced past it. Advancing first
     * would record every transition as a no-op against itself.
     */
    public void record(final String client, final UUID reportId, final ManStateRow row) {
        history.appendIfAbsent(reportId, client, row.mandateRef(), row.state());
        ledger.record(reportId, client, row.mandateRef(), row.state(), null);
        watermarks.upsertWatermark(client, row.mandateRef(), row.state());
    }
}
