package za.co.fnb.dcre.mrg.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import za.co.fnb.dcre.mrg.data.AbstractMrgCrdbIT;
import za.co.fnb.dcre.mrg.data.repo.CollectionOutcomeDao;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live regression of 2026-07-26, reproduced on the WHOLE context rather than on the
 * ColDatasourceConfig slice: every MRG report window started failing one per minute with
 * "set DCRE_COL_DB_URL: running in-cluster ...", because the collections beans were
 * unconditional while the variable is injected LAUNCH-scoped onto the suspension sweep only.
 *
 * <p>This is a report-window pod exactly as AGT launches it: no {@code DCRE_COL_DB_URL},
 * {@code spring.batch.job.name} left at the committed yml default ({@code mrgJob}, since AGT
 * overrides {@code DCRE_MRG_JOB_NAME} on the sweep launch only), and
 * {@code KUBERNETES_SERVICE_HOST} set as kubelet sets it in every container.</p>
 *
 * <p>The slice test alone would not have caught the whole bug: gating only the datasource
 * config MOVES the failure onto the sweep service chain that consumes the DAO, and onto the
 * {@code mrgSuspendJob} bean that consumes the tasklet. Only a full context start proves the
 * report launch resolves with the entire chain absent.</p>
 */
@TestPropertySource(properties = "KUBERNETES_SERVICE_HOST=10.96.0.1")
class ReportLaunchContextIT extends AbstractMrgCrdbIT {

    @Autowired
    ApplicationContext context;

    @Test
    void theReportLaunchStartsInClusterWithNoCollectionsWiring() {
        assertThat(context.getBeanNamesForType(CollectionOutcomeDao.class))
                .as("the report window never reads dcre_col, so it declares no collections DAO")
                .isEmpty();
    }

    @Test
    void theReportLaunchDeclaresItsOwnJobAndNotTheSweep() {
        assertThat(context.containsBean("mrgJob")).isTrue();
        assertThat(context.containsBean("mrgSuspendJob"))
                .as("the sweep job is launch-scoped with the collections wiring it needs")
                .isFalse();
    }
}
