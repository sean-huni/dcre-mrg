package za.co.fnb.dcre.mrg.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fnb.dcre.mrg.data.model.ManStateRow;
import za.co.fnb.dcre.mrg.domain.ManReportLayout;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * R-29 second phase (prg-12 honesty): the EMITTED file is the only truth of
 * what was externally reported, so the advance streams the committed target
 * back and ledgers/watermarks exactly those (mandate_ref, state) tuples in
 * per-slice REQUIRES_NEW transactions (fresh tx per CrdbRetry attempt). A
 * mandate whose state moved AFTER the file was written is never ledgered here;
 * it rides the next window. The same path replays after a crash: a standing
 * target is advanced as written (ledger ON CONFLICT DO NOTHING + idempotent
 * watermark UPSERT make it a zero-dup no-op). What ONE advanced mandate durably
 * records is {@link ManAdvanceRecorder}'s responsibility, not this class's.
 */
@Service
public class ManDeliveryAdvancer {

    private final ManAdvanceRecorder recorder;
    private final TransactionTemplate advanceTx;
    private final int sliceSize;

    public ManDeliveryAdvancer(final ManAdvanceRecorder recorder,
                               final PlatformTransactionManager txManager,
                               @Value("${dcre.mrg.slice-size:5000}") final int sliceSize) {
        this.recorder = recorder;
        // Each advance attempt needs its OWN transaction: a CRDB 40001 abort poisons the
        // surrounding transaction (25P02 on any further statement), so retrying inside a
        // shared transaction can never succeed.
        this.advanceTx = new TransactionTemplate(txManager);
        this.advanceTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.sliceSize = sliceSize;
    }

    public void advanceFromEmittedFile(final String client, final Path target, final UUID reportId)
            throws IOException {
        try (BufferedReader in = Files.newBufferedReader(target)) {
            final List<ManStateRow> batch = new ArrayList<>(sliceSize);
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.startsWith(ManReportLayout.DETAIL_PREFIX)) {
                    continue; // header/trailer; heartbeats never reach this phase
                }
                final String[] parts = line.split("\\|", 3);
                batch.add(new ManStateRow(parts[1], parts[2]));
                if (batch.size() == sliceSize) {
                    advanceSlice(client, reportId, List.copyOf(batch));
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                advanceSlice(client, reportId, batch);
            }
        }
    }

    /** History + ledger + watermark per advanced mandate, atomically per slice (see the recorder). */
    private void advanceSlice(final String client, final UUID reportId, final List<ManStateRow> slice) {
        CrdbRetry.run("advance client=%s from=%s".formatted(client, slice.getFirst().mandateRef()),
                () -> advanceTx.executeWithoutResult(status -> {
                    for (final ManStateRow row : slice) {
                        recorder.record(client, reportId, row);
                    }
                }));
    }
}
