package za.co.fnb.dcre.mrg.service;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Thin entry adapter for the suspension sweep (3-tier). All the collections-read and
 * decision logic is in the service tier.
 *
 * <p>Ported from MSR minus its {@code sweep.instant} parameter: that instant tokenised the
 * status-history full-identity key, and there is no history table here. The override write
 * is keyed on (mandate_ref, source) alone, which is the whole business identity, so a
 * re-run needs no token to stay zero-duplicate.</p>
 */
@Component
public class SuspensionTasklet implements Tasklet {

    private final ManSuspensionService service;

    public SuspensionTasklet(final ManSuspensionService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final int suspended = service.sweep();
        chunkContext.getStepContext().getStepExecution().getJobExecution()
                .getExecutionContext().putInt("suspended", suspended);
        return RepeatStatus.FINISHED;
    }
}
