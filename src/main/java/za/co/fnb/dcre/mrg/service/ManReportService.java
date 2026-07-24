package za.co.fnb.dcre.mrg.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mrg.data.model.ManStateRow;
import za.co.fnb.dcre.mrg.domain.ManReportLayout;
import za.co.fnb.dcre.platform.files.ExchangeChannel;
import za.co.fnb.dcre.platform.files.ExchangeLayout;
import za.co.fnb.dcre.platform.files.ExchangeSub;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Business tier: per-client mandate state-delta emission on clock windows
 * (plan T14). Reports each mandate whose projection state moved past the
 * client's last reported state; a quiet window emits the zero-delta heartbeat.
 * Deterministic report identity (client, window) -> a re-run of the same
 * window re-emits the SAME single file (SCRUM-90: never epoch/UUID in the
 * identifying key). R-29 order: the WHOLE file becomes visible first (StagedWrite
 * temp + ATOMIC_MOVE), then the watermark/ledger advance from the emitted file.
 * A crash between the two phases replays as skip-existing-file + advance-from-file.
 * Deliberate 4-dependency aggregator (orchestrator): writer, advancer, registry, layout.
 */
@Service
public class ManReportService {

    private static final Logger log = LoggerFactory.getLogger(ManReportService.class);

    private final ManStateDeltaWriter writer;
    private final ManDeliveryAdvancer advancer;
    private final ManReportRegistry registry;
    private final ExchangeLayout layout;

    public ManReportService(final ManStateDeltaWriter writer, final ManDeliveryAdvancer advancer,
                            final ManReportRegistry registry, final ExchangeLayout layout) {
        this.writer = writer;
        this.advancer = advancer;
        this.registry = registry;
        this.layout = layout;
    }

    /** Job-less convenience (direct-service callers/tests): no batch execution, so job_name stays null. */
    public Optional<Path> window(final String client, final String windowKey, final boolean resend)
            throws IOException {
        return window(client, windowKey, resend, null);
    }

    /**
     * @return the emitted report path: the windowed state-delta, or the zero-delta heartbeat.
     */
    public Optional<Path> window(final String client, final String windowKey, final boolean resend,
                                 final String jobName) throws IOException {
        final Path target = out(client).resolve(ManReportLayout.fileName(client, windowKey));
        final List<ManStateRow> first = writer.firstSlice(client, resend);
        if (first.isEmpty()) {
            return Optional.of(heartbeat(client, windowKey, target, jobName));
        }
        if (heartbeatStands(target)) {
            // Kill-resume guard: a heartbeat already stands for this window (R-24: never
            // rewritten). Ledgering/advancing the late delta against a zero-MND file would
            // record mandate states as externally delivered that never reached the client;
            // the delta flows untouched to the NEXT window instead.
            log.info("report stage=MRG type=SCHEDULED client={} window={} reason=HEARTBEAT_STANDS"
                    + " action=defer-delta", client, windowKey);
            return Optional.of(heartbeat(client, windowKey, target, jobName));
        }
        if (!Files.exists(target)) {
            writer.writeDelta(client, windowKey, resend, target, first);
        }
        advancer.advanceFromEmittedFile(client, target,
                registry.openReport(client, "SCHEDULED", "CLOCK", windowKey,
                        target.getFileName().toString(), jobName));
        return Optional.of(target);
    }

    private Path heartbeat(final String client, final String windowKey, final Path target,
                           final String jobName) throws IOException {
        writer.writeHeartbeat(client, windowKey, target);
        registry.openReport(client, "HEARTBEAT", "CLOCK", windowKey,
                target.getFileName().toString(), jobName);
        log.info("report stage=MRG type=HEARTBEAT client={} window={} file={}",
                client, windowKey, target.getFileName());
        return target;
    }

    /**
     * True when this window's standing artifact is a heartbeat: registry row
     * type HEARTBEAT, or (crash between the heartbeat ATOMIC_MOVE and the
     * registry insert) a standing file whose trailer is END|0. A real delta
     * file always carries at least one MND line, so END|0 uniquely classifies a
     * heartbeat; the trailer check only runs when no registry row exists.
     */
    private boolean heartbeatStands(final Path target) throws IOException {
        final Optional<String> standing = registry.standingType(target.getFileName().toString());
        if (standing.isPresent()) {
            return "HEARTBEAT".equals(standing.get());
        }
        return ManReportLayout.trailer(0).equals(writer.standingTrailer(target));
    }

    private Path out(final String client) {
        return layout.resolve(client, ExchangeChannel.ONHOST_RESP_MAN, ExchangeSub.OUT);
    }
}
