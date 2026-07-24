package za.co.fnb.dcre.mrg.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.mrg.data.model.ManReportEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Mandate report registry: every emitted report artifact, replay-addressable by id. */
public interface ManReportRepo extends CrudRepository<ManReportEntity, UUID> {

    /** file_name is unique: the restart/replay no-op lookup (a standing row means the emission registered). */
    Optional<ManReportEntity> findByFileName(String fileName);

    /** Clock-scoped trace: every report artifact emitted under one batch execution, by its seam job_name. */
    List<ManReportEntity> findByJobName(String jobName);
}
