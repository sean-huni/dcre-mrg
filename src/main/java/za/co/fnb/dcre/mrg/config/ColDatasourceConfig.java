package za.co.fnb.dcre.mrg.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import za.co.fnb.dcre.mrg.data.repo.CollectionOutcomeDao;

/**
 * The SECOND, read-only datasource pointed at the collections DB (dcre_col) for the
 * suspension sweep. Ported from MSR unchanged apart from its package (SCRUM-91: MRG
 * absorbed msrSuspendJob). Cloned from {@code HeartbeatDatasourceConfig}: the datasource is
 * built INLINE (never a {@code DataSource} bean), so Boot's
 * {@code DataSourceAutoConfiguration} ({@code @ConditionalOnMissingBean(DataSource.class)})
 * does not replace the primary dcre_man datasource, and Liquibase / Batch / Spring-Data all
 * stay on the primary. A deliberately non-pooling {@link SimpleDriverDataSource}: the sweep
 * issues a few short reads, and it only connects when first queried, so an unused col target
 * never fails MRG's report job. The resolved target is logged once (INFO) so operators can
 * see it.
 *
 * <p>Dedicated {@code dcre.col-db-*} credentials keep the collections read grant distinct
 * from the primary dcre_man credential.</p>
 *
 * <p><b>The dev default is guarded, not dropped.</b> The localhost default is what lets a
 * clean clone boot with no {@code .env} (12FactorApp Alignment - https://12factor.net/),
 * the same reasoning {@code HeartbeatDatasourceConfig} states for {@code dcre.agtops-db-url}
 * ("an infrastructure address, not a table prefix, so a masking default is safe here").
 * But safe there rests on "every deployed context overrides it", and here nothing did: AGT
 * shipped no {@code DCRE_COL_DB_URL}, so every in-cluster sweep window quietly aimed at
 * localhost and died with "Connection to localhost:26257 refused" (found live 2026-07-26).
 * So the default survives for local dev and becomes FATAL in a pod: if
 * {@code KUBERNETES_SERVICE_HOST} is set (kubelet sets it in every container) and the url
 * is still the committed dev default, the context fails at start naming the variable. That
 * keeps {@link #LOCAL_DEV_URL} the single pivot, pinned to the yml by a parity test, rather
 * than removing the default and breaking the clean-clone rule for every local run.</p>
 *
 * <p><b>{@link OnSuspendSweep}: these beans are launch-scoped, exactly like the variable.</b>
 * They used to be unconditional, so a report-window pod built them too and the guard fired
 * correctly on a pod that was deliberately never given the URL, taking every report window
 * down (live 2026-07-26). The bean's scope now matches the env's scope; the guard is
 * unchanged, because the guard is what surfaced this.</p>
 */
@OnSuspendSweep
@Configuration(proxyBeanMethods = false)
public class ColDatasourceConfig {

    /** The committed local-dev target; kept verbatim as the placeholder default so a
     *  clean clone boots with no {@code .env}. Pinned to the yml by a parity test. */
    static final String LOCAL_DEV_URL = "jdbc:postgresql://localhost:26257/dcre_col?sslmode=disable";

    private static final Logger log = LoggerFactory.getLogger(ColDatasourceConfig.class);

    @Bean
    CollectionOutcomeDao collectionOutcomeDao(
            @Value("${dcre.col-db-url:" + LOCAL_DEV_URL + "}") final String url,
            @Value("${dcre.col-db-user:root}") final String user,
            @Value("${dcre.col-db-password:}") final String password,
            @Value("${KUBERNETES_SERVICE_HOST:}") final String clusterApiHost) {
        requireWiredTargetInCluster(url, clusterApiHost);
        log.info("suspension sweep: dcre_col (read-only) target url={} user={}", url, user);
        final DataSource col = DataSourceBuilder.create()
                .type(SimpleDriverDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(url).username(user).password(password).build();
        return new CollectionOutcomeDao(new JdbcTemplate(col));
    }

    /** Fail fast in a pod that is still on the local-dev default: the sweep would
     *  otherwise start, log the wrong target, and fail every window at query time. */
    private static void requireWiredTargetInCluster(final String url, final String clusterApiHost) {
        if (clusterApiHost == null || clusterApiHost.isBlank() || !LOCAL_DEV_URL.equals(url)) {
            return;
        }
        throw new IllegalStateException(
                "set DCRE_COL_DB_URL: running in-cluster (KUBERNETES_SERVICE_HOST=" + clusterApiHost
                        + ") on the local-dev dcre_col default " + LOCAL_DEV_URL
                        + "; the suspension sweep reads the collections DB and localhost is not it");
    }
}
