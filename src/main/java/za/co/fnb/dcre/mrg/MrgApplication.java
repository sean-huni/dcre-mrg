package za.co.fnb.dcre.mrg;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import za.co.fnb.dcre.mrg.config.ColDatasourceConfig;
import za.co.fnb.dcre.platform.batch.ExitCodeMain;
import za.co.fnb.dcre.platform.batch.config.BatchJdbcConfig;
import za.co.fnb.dcre.platform.batch.config.HeartbeatDatasourceConfig;
import za.co.fnb.dcre.platform.persistence.JdbcConfig;

/**
 * MRG (Mandate Report Generator). Clock-launched: it emits the per-client mandate
 * state-delta report and, since SCRUM-91, runs the suspension sweep absorbed from MSR. The
 * primary datasource writes dcre_man; {@link ColDatasourceConfig} adds a SECOND read-only
 * datasource for the sweep against dcre_col (never a {@code DataSource} bean, so Liquibase /
 * Batch / Spring-Data stay on the primary).
 */
@SpringBootApplication
@Import({JdbcConfig.class, BatchJdbcConfig.class, HeartbeatDatasourceConfig.class,
        ColDatasourceConfig.class})
public class MrgApplication {

    public static void main(final String[] args) {
        ExitCodeMain.run(MrgApplication.class, args);
    }
}
