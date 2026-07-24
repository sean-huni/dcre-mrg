package za.co.fnb.dcre.mrg.service;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.platform.batch.OutcomeFileWriter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Thin entry adapter (3-tier). Job identity: (client, window); everything else
 * is a non-identifying override. Dispatch on report.type: SCHEDULED (default) =
 * the clock-window state-delta path (resend "true" re-emits all current
 * mandate states); REPLAY = re-emit an existing report by report.id.
 */
@Component
public class ManReportTasklet implements Tasklet {

    private final ManReportService service;
    private final ManReplayService replay;

    public ManReportTasklet(final ManReportService service, final ManReplayService replay) {
        this.service = service;
        this.replay = replay;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext)
            throws Exception {
        final Map<String, Object> params = chunkContext.getStepContext().getJobParameters();
        // The seam job name (env JOB_NAME or local-mrg-<executionId>) is resolved once here in
        // the adapter and threaded into every report row so it commits in the same transaction.
        final long executionId = chunkContext.getStepContext().getStepExecution()
                .getJobExecution().getId();
        final String jobName = OutcomeFileWriter.jobNameOrLocal("mrg", executionId);
        final String type = (String) params.getOrDefault("report.type", "SCHEDULED");
        final String emitted = switch (type) {
            case "SCHEDULED" -> scheduled(params, jobName);
            case "REPLAY" -> replay.replay(UUID.fromString((String) params.get("report.id"))).toString();
            default -> throw new IllegalArgumentException("unsupported report.type=%s".formatted(type));
        };
        chunkContext.getStepContext().getStepExecution().getJobExecution()
                .getExecutionContext().putString("mrg.file", emitted);
        return RepeatStatus.FINISHED;
    }

    private String scheduled(final Map<String, Object> params, final String jobName) throws IOException {
        final String client = (String) params.get("client");
        final String window = (String) params.get("window");
        final boolean resend = "true".equals(params.get("resend"));
        return service.window(client, window, resend, jobName).map(Object::toString).orElse("NONE");
    }
}
