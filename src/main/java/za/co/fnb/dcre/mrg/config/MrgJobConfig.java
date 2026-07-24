package za.co.fnb.dcre.mrg.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.mrg.service.ManReportTasklet;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;

@Configuration
public class MrgJobConfig {

    @Bean
    public Job mrgJob(final JobRepository repo, final PlatformTransactionManager tx,
                      final ManReportTasklet tasklet, final HeartbeatWriter heartbeatWriter,
                      @Value("${dcre.exchange-root}") final String exchangeRoot) {
        final Step reportStep = new StepBuilder("mrgReportStep", repo).tasklet(tasklet, tx).build();
        // The shared seam listener gates on COMPLETED and self-describes the local seam name as
        // local-mrg-<executionId> when JOB_NAME is absent; verdict BUSINESS_ACCEPTED.
        return new JobBuilder("mrgJob", repo)
                .listener(new OutcomeSeamListener("mrg", exchangeRoot, execution -> "BUSINESS_ACCEPTED"))
                .listener(heartbeatWriter)
                .start(reportStep)
                .build();
    }
}
