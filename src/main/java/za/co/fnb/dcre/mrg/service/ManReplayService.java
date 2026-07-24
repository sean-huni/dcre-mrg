package za.co.fnb.dcre.mrg.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mrg.data.model.ManReportEntity;
import za.co.fnb.dcre.mrg.data.model.ManStateRow;
import za.co.fnb.dcre.mrg.data.repo.ManDeliveryLedgerRepo;
import za.co.fnb.dcre.mrg.data.repo.ManReportRepo;
import za.co.fnb.dcre.mrg.domain.ManReportLayout;
import za.co.fnb.dcre.platform.files.ExchangeChannel;
import za.co.fnb.dcre.platform.files.ExchangeLayout;
import za.co.fnb.dcre.platform.files.ExchangeSub;
import za.co.fnb.dcre.platform.files.StagedWrite;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Replay-by-report-id: re-emits the SAME report artifact deterministically from
 * its delivery ledger (the exact-replay source, the authority on what was
 * externally reported). Idempotent: an existing target is an R-24 no-op, so a
 * re-run reproduces one identical file. The report id is the stable address
 * (never epoch/UUID minted per run, SCRUM-90).
 */
@Service
public class ManReplayService {

    private static final Logger log = LoggerFactory.getLogger(ManReplayService.class);

    private final ManReportRepo reports;
    private final ManDeliveryLedgerRepo ledger;
    private final ExchangeLayout layout;

    public ManReplayService(final ManReportRepo reports, final ManDeliveryLedgerRepo ledger,
                            final ExchangeLayout layout) {
        this.reports = reports;
        this.ledger = ledger;
        this.layout = layout;
    }

    public Path replay(final UUID reportId) throws IOException {
        final ManReportEntity report = reports.findById(reportId).orElseThrow(
                () -> new NoSuchElementException("no man_report for id " + reportId));
        final Path target = layout.resolve(report.getClient(), ExchangeChannel.ONHOST_RESP_MAN,
                ExchangeSub.OUT).resolve(report.getFileName());
        final List<ManStateRow> rows = ledger.rowsForReport(reportId);
        final List<String> lines = new ArrayList<>();
        lines.add(ManReportLayout.header(report.getClient(), report.getWindowKey()));
        for (final ManStateRow row : rows) {
            lines.add(ManReportLayout.detail(row.mandateRef(), row.state()));
        }
        lines.add(ManReportLayout.trailer(rows.size()));
        StagedWrite.write(target, lines);
        log.info("report stage=MRG type=REPLAY client={} report={} file={} rows={}",
                report.getClient(), reportId, report.getFileName(), rows.size());
        return target;
    }
}
