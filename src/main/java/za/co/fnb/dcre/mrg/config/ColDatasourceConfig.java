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
 */
@Configuration(proxyBeanMethods = false)
public class ColDatasourceConfig {

    private static final Logger log = LoggerFactory.getLogger(ColDatasourceConfig.class);

    @Bean
    CollectionOutcomeDao collectionOutcomeDao(
            @Value("${dcre.col-db-url:jdbc:postgresql://localhost:26257/dcre_col?sslmode=disable}") final String url,
            @Value("${dcre.col-db-user:root}") final String user,
            @Value("${dcre.col-db-password:}") final String password) {
        log.info("suspension sweep: dcre_col (read-only) target url={} user={}", url, user);
        final DataSource col = DataSourceBuilder.create()
                .type(SimpleDriverDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(url).username(user).password(password).build();
        return new CollectionOutcomeDao(new JdbcTemplate(col));
    }
}
