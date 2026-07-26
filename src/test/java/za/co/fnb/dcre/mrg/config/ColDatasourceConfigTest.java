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
 */
class ColDatasourceConfigTest {

    /** Any non-blank value; kubelet sets the API server's ClusterIP here. */
    private static final String IN_CLUSTER = "KUBERNETES_SERVICE_HOST=10.96.0.1";

    private static final String WIRED_URL =
            "dcre.col-db-url=jdbc:postgresql://crdb.dcre.svc.cluster.local:26257/dcre_col?sslmode=disable";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ColDatasourceConfig.class);

    @Test
    void inClusterOnTheDevDefaultFailsAtStartupNamingTheVariable() {
        runner.withPropertyValues(IN_CLUSTER).run(context -> assertThat(context)
                .getFailure()
                .hasMessageContaining("DCRE_COL_DB_URL"));
    }

    @Test
    void inClusterWithTheUrlWiredStartsNormally() {
        runner.withPropertyValues(IN_CLUSTER, WIRED_URL).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CollectionOutcomeDao.class);
        });
    }

    @Test
    void localDevKeepsTheCommittedDefaultAndBoots() {
        // Clean-clone rule: no .env, no cluster, still boots. The guard must not
        // turn the local inner loop into a mandatory-env chore.
        runner.run(context -> {
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

    private static String applicationYml() {
        try (InputStream in = ColDatasourceConfig.class.getResourceAsStream("/application.yml")) {
            assertThat(in).as("mrg application.yml on the test classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException("cannot read application.yml", e);
        }
    }
}
