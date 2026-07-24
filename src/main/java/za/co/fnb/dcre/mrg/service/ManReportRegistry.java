package za.co.fnb.dcre.mrg.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fnb.dcre.mrg.data.model.ManReportEntity;
import za.co.fnb.dcre.mrg.data.repo.ManReportRepo;

import java.util.Optional;
import java.util.UUID;

/**
 * Report registry access: find-or-save one man_report row per emitted artifact
 * (file_name is unique) in its OWN transaction, so a restart reuses the
 * standing row rather than duplicating it. The registry row is the
 * replay-address of a report artifact.
 */
@Service
public class ManReportRegistry {

    private final ManReportRepo reports;
    private final TransactionTemplate registryTx;

    public ManReportRegistry(final ManReportRepo reports, final PlatformTransactionManager txManager) {
        this.reports = reports;
        this.registryTx = new TransactionTemplate(txManager);
        this.registryTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Find-or-save on the unique file_name so restarts reuse the row; returns the report id. */
    public UUID openReport(final String client, final String reportType, final String triggerKind,
                           final String windowKey, final String fileName, final String jobName) {
        return registryTx.execute(s -> reports.findByFileName(fileName)
                .orElseGet(() -> reports.save(ManReportEntity.of(
                        client, reportType, triggerKind, windowKey, fileName, jobName)))
                .getId());
    }

    /** report_type of a standing artifact, if one is already registered for this file. */
    public Optional<String> standingType(final String fileName) {
        return reports.findByFileName(fileName).map(ManReportEntity::getReportType);
    }
}
