package za.co.fnb.dcre.mrg.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mrg.data.model.ManStateRow;
import za.co.fnb.dcre.mrg.data.repo.ManWatermarkRepo;
import za.co.fnb.dcre.mrg.domain.ManReportLayout;
import za.co.fnb.dcre.platform.files.StagedWrite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds and StagedWrites the mandate state-delta file (v1: whole file rendered
 * in heap, then temp + fsync + ATOMIC_MOVE via platform-files StagedWrite;
 * StreamedPsrWrite is the future path if mandate volumes ever demand streaming).
 * Reads the projection delta in bounded keyset slices so the read stays
 * memory-bounded even while the rendered file does not.
 */
@Service
public class ManStateDeltaWriter {

    private final ManWatermarkRepo watermarks;
    private final int sliceSize;

    public ManStateDeltaWriter(final ManWatermarkRepo watermarks,
                               @Value("${dcre.mrg.slice-size:5000}") final int sliceSize) {
        this.watermarks = watermarks;
        this.sliceSize = sliceSize;
    }

    /** First keyset slice of the delta (or the full range on resend); empty means a quiet window. */
    public List<ManStateRow> firstSlice(final String client, final boolean resend) {
        return readSlice(client, resend, "");
    }

    /** Renders header + one MND line per delta mandate + trailer, then stages it atomically to target. */
    public void writeDelta(final String client, final String windowKey, final boolean resend,
                           final Path target, final List<ManStateRow> firstSlice) throws IOException {
        final List<String> lines = new ArrayList<>();
        lines.add(ManReportLayout.header(client, windowKey));
        List<ManStateRow> slice = firstSlice;
        long count = 0;
        while (true) {
            for (final ManStateRow row : slice) {
                lines.add(ManReportLayout.detail(row.mandateRef(), row.state()));
                count++;
            }
            if (slice.size() < sliceSize) {
                break;
            }
            slice = readSlice(client, resend, slice.getLast().mandateRef());
        }
        lines.add(ManReportLayout.trailer(count));
        StagedWrite.write(target, lines);
    }

    /** Zero-delta HEARTBEAT (SYNTHETIC A-57): header + HB placeholder + END|0; R-24 no-op if it stands. */
    public Path writeHeartbeat(final String client, final String windowKey, final Path target)
            throws IOException {
        StagedWrite.write(target, List.of(ManReportLayout.header(client, windowKey),
                ManReportLayout.heartbeatLine(), ManReportLayout.trailer(0)));
        return target;
    }

    /** Trailer line of a standing file (END|0 classifies a heartbeat even with no registry row); null if absent. */
    public String standingTrailer(final Path target) throws IOException {
        if (!Files.exists(target)) {
            return null;
        }
        final List<String> lines = Files.readAllLines(target);
        return lines.isEmpty() ? null : lines.getLast();
    }

    private List<ManStateRow> readSlice(final String client, final boolean resend, final String afterRef) {
        return resend
                ? watermarks.findRangeSlice(client, afterRef, sliceSize)
                : watermarks.findDeltaSlice(client, afterRef, sliceSize);
    }
}
