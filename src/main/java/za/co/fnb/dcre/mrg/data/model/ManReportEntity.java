package za.co.fnb.dcre.mrg.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Mandate report registry: one row per emitted report artifact
 * (type SCHEDULED | HEARTBEAT). Plain aggregate, NOT a BaseEntity:
 * man_report is append-only (no version/updated_at columns), so new-ness rides
 * {@link Persistable} instead of the @Version heuristic and the factory assigns
 * id + created_at client-side. file_name is unique: the replay/restart no-op key.
 */
@Table("man_report")
public class ManReportEntity implements Persistable<UUID> {

    @Id
    private UUID id;
    private String client;
    private String type;
    private String triggerKind;
    private String windowKey;
    private String fileName;
    private String jobName;
    private Instant createdAt;

    @Transient
    private boolean isNew;

    /**
     * @param jobName clock-scoped trace anchor (env JOB_NAME or {@code local-mrg-<executionId>},
     *                resolved by the owning tasklet); persisted in the SAME transaction as the
     *                report row so a supporter can join a report file to its launching job.
     */
    public static ManReportEntity of(final String client, final String type,
            final String triggerKind, final String windowKey, final String fileName,
            final String jobName) {
        final ManReportEntity r = new ManReportEntity();
        r.id = UUID.randomUUID();
        r.isNew = true;
        r.client = client;
        r.type = type;
        r.triggerKind = triggerKind;
        r.windowKey = windowKey;
        r.fileName = fileName;
        r.jobName = jobName;
        r.createdAt = Instant.now();
        return r;
    }

    @Override
    public UUID getId() { return id; }

    @Override
    public boolean isNew() { return isNew; }

    public String getClient() { return client; }
    public String getType() { return type; }
    public String getTriggerKind() { return triggerKind; }
    public String getWindowKey() { return windowKey; }
    public String getFileName() { return fileName; }
    public String getJobName() { return jobName; }
    public Instant getCreatedAt() { return createdAt; }
}
