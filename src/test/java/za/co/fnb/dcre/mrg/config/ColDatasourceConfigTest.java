package za.co.fnb.dcre.mrg.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import za.co.fnb.dcre.mrg.data.repo.CollectionOutcomeDao;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The suspension sweep's SECOND, read-only dcre_col datasource keeps a committed
 * localhost default so a clean clone boots with no {@code .env} (12FactorApp Alignment
 * - https://12factor.net/), but that default MASKED a real misconfiguration: AGT never
 * injected {@code DCRE_COL_DB_URL}, so every in-cluster sweep window silently pointed at
 * localhost and died with "Connection to localhost:26257 refused" (found live 2026-07-26).
 *
 * <p>The guard is the narrowest thing that closes it: the dev default is fatal ONLY when
 * the process runs inside a pod ({@code KUBERNETES_SERVICE_HOST}, set by kubelet in every
 * container). Local dev is untouched; a wired cluster is untouched; an unwired cluster
 * fails at context start naming the variable, instead of once per window at query time.</p>
 *
 * <p><b>Both directions are pinned deliberately.</b> AGT injects the variable LAUNCH-scoped,
 * onto the sweep launch only, because {@code Stage.MRG} also serves the report windows and
 * those never read dcre_col. The beans must therefore be launch-scoped too, or the guard
 * fires on a pod that never needed the connection: that is exactly what took every report
 * window down on 2026-07-26. One test per direction, so the next reader cannot "fix" one by
 * breaking the other.</p>
 */
class ColDatasourceConfigTest {

    /** Any non-blank value; kubelet sets the API server's ClusterIP here. */
    private static final String IN_CLUSTER = "KUBERNETES_SERVICE_HOST=10.96.0.1";

    /** The committed default launch: the clock-window report, which reads dcre_man only. */
    private static final String REPORT_JOB = "mrgJob";

    /** The launch AGT gives the variable to: the only launch that reads dcre_col. */
    private static final String SUSPEND_LAUNCH =
            OnSuspendSweep.JOB_NAME + "=" + OnSuspendSweep.SUSPEND_JOB;

    private static final String REPORT_LAUNCH = OnSuspendSweep.JOB_NAME + "=" + REPORT_JOB;

    private static final String WIRED_URL =
            "dcre.col-db-url=jdbc:postgresql://crdb.dcre.svc.cluster.local:26257/dcre_col?sslmode=disable";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ColDatasourceConfig.class);

    /**
     * The live regression (2026-07-26, every MRG report window TECH_FAILED one per minute).
     * A report-window pod is in-cluster and carries no DCRE_COL_DB_URL by design, so an
     * UNCONDITIONAL col bean makes the guard fire on a pod with no business connecting to
     * the collections DB. The bean must not exist on this launch at all.
     */
    @Test
    void theReportLaunchInClusterNeedsNoCollectionsUrlAndDeclaresNoColBean() {
        runner.withPropertyValues(REPORT_LAUNCH, IN_CLUSTER).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CollectionOutcomeDao.class);
        });
    }

    @Test
    void inClusterOnTheDevDefaultFailsAtStartupNamingTheVariable() {
        runner.withPropertyValues(SUSPEND_LAUNCH, IN_CLUSTER).run(context -> assertThat(context)
                .getFailure()
                .hasMessageContaining("DCRE_COL_DB_URL"));
    }

    @Test
    void inClusterWithTheUrlWiredStartsNormally() {
        runner.withPropertyValues(SUSPEND_LAUNCH, IN_CLUSTER, WIRED_URL).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CollectionOutcomeDao.class);
        });
    }

    @Test
    void localDevKeepsTheCommittedDefaultAndBoots() {
        // Clean-clone rule: no .env, no cluster, still boots. The guard must not
        // turn the local inner loop into a mandatory-env chore.
        runner.withPropertyValues(SUSPEND_LAUNCH).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CollectionOutcomeDao.class);
        });
    }

    @Test
    void theGuardedConstantIsTheCommittedYmlDefault() {
        // Parity: the guard fires on equality with the dev default, so a yml edit
        // that changed the default without changing the constant would silently
        // disarm it (dev-credential-banner redaction-guard pattern).
        assertThat(applicationYml())
                .contains("col-db-url: ${DCRE_COL_DB_URL:" + ColDatasourceConfig.LOCAL_DEV_URL + "}");
    }

    @Test
    void theGatedLaunchSelectorIsTheCommittedYmlBinding() {
        // Same disarm risk as the guard constant above, one level out: the gate matches on
        // the launch selector, which the yml binds to AGT's DCRE_MRG_JOB_NAME and defaults
        // to the report job. Rename that binding and a sweep pod would quietly select the
        // report launch, taking the sweep beans away from the job that needs them.
        assertThat(applicationYml()).contains("name: ${DCRE_MRG_JOB_NAME:" + REPORT_JOB + "}");
    }

    private static String applicationYml() {
        try (InputStream in = ColDatasourceConfig.class.getResourceAsStream("/application.yml")) {
            assertThat(in).as("mrg application.yml on the test classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException("cannot read application.yml", e);
        }
    }
}
