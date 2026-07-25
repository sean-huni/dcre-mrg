package za.co.fnb.dcre.mrg.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.mrg.service.ManReportTasklet;
import za.co.fnb.dcre.mrg.service.SuspensionTasklet;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;

/**
 * MRG's two job types, each a single-tasklet step (thin adapter -> service). The report job
 * is the clock-window state-delta emitter; the suspension sweep is the writer MRG absorbed
 * from msrSuspendJob (SCRUM-91). AGT selects which one runs via {@code spring.batch.job.name}
 * (default {@code mrgJob}); the ITs launch a specific job explicitly through the JobOperator.
 */
@Configuration
public class MrgJobConfig {

    private final String exchangeRoot;

    public MrgJobConfig(@Value("${dcre.exchange-root}") final String exchangeRoot) {
        this.exchangeRoot = exchangeRoot;
    }

    @Bean
    public Job mrgJob(final JobRepository repo, final PlatformTransactionManager tx,
                      final ManReportTasklet tasklet, final HeartbeatWriter heartbeatWriter) {
        return job("mrgJob", "mrg", repo, heartbeatWriter,
                taskletStep("mrgReportStep", repo, tx, tasklet));
    }

    @Bean
    public Job mrgSuspendJob(final JobRepository repo, final PlatformTransactionManager tx,
                             final SuspensionTasklet tasklet, final HeartbeatWriter heartbeatWriter) {
        return job("mrgSuspendJob", "mrg-suspend", repo, heartbeatWriter,
                taskletStep("mrgSuspendStep", repo, tx, tasklet));
    }

    private Step taskletStep(final String name, final JobRepository repo,
                             final PlatformTransactionManager tx, final Tasklet tasklet) {
        return new StepBuilder(name, repo).tasklet(tasklet, tx).build();
    }

    // The shared seam listener gates on COMPLETED and self-describes the local seam name as
    // local-<svc>-<executionId> when JOB_NAME is absent; verdict BUSINESS_ACCEPTED.
    private Job job(final String jobName, final String svc, final JobRepository repo,
                    final HeartbeatWriter heartbeatWriter, final Step step) {
        return new JobBuilder(jobName, repo)
                .listener(new OutcomeSeamListener(svc, exchangeRoot, execution -> "BUSINESS_ACCEPTED"))
                .listener(heartbeatWriter)
                .start(step)
                .build();
    }
}
