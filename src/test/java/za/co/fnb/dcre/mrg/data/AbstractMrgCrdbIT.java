package za.co.fnb.dcre.mrg.data;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

/**
 * Shared Testcontainers base for MRG's view-contract and delta suites (SCRUM-91). MRG had
 * NO shared base class: MrgJobTest, ManStateDeltaServiceIT and bdd/CucumberSpringConfig
 * each carry their own static CockroachContainer plus @DynamicPropertySource. This factors
 * that bootstrap out once for the suites that need the real migrated schema.
 *
 * <p>Everything seeds REAL tables through the real Liquibase-migrated schema: no mocks, no
 * hand-built view sources. The views under test are therefore proved against the shape the
 * owning services actually create. The seed bodies live in {@link ManSpineFixture} and
 * {@link ManLegFixture} because the suites that do NOT extend this base need them too.</p>
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false",
        "dcre.exchange-root=build/test-exchange", "DCRE_EXCHANGE_ROOT=build/test-exchange"})
public abstract class AbstractMrgCrdbIT {

    protected static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
    }

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    protected UUID seedSpine(final String client, final String mandateRef, final String mndtReqId) {
        return ManSpineFixture.seedSpine(jdbc, client, mandateRef, mndtReqId);
    }

    protected UUID seedSpineWithDates(final String client, final String mandateRef,
                                      final String mndtReqId, final String startDate,
                                      final String expiryDate) {
        return ManSpineFixture.seedSpineWithDates(jdbc, client, mandateRef, mndtReqId, startDate, expiryDate);
    }

    protected void seedDupEntry(final UUID arrival, final int sequence, final String mandateRef) {
        ManSpineFixture.seedDupEntry(jdbc, arrival, sequence, mandateRef);
    }

    protected void seedVerdict(final UUID arrival, final int sequence, final String outcome,
                               final String detail) {
        ManSpineFixture.seedVerdict(jdbc, arrival, sequence, outcome, detail);
    }

    protected void seedOverride(final String mandateRef, final String state, final String reason,
                                final String source, final String effectiveFrom) {
        ManSpineFixture.seedOverride(jdbc, mandateRef, state, reason, source, effectiveFrom);
    }

    protected void seedIsr(final String mndtReqId, final String status, final String reason) {
        ManLegFixture.seedLeg(jdbc, "man_isr_resp", mndtReqId, status, reason, mndtReqId + "_ISR.xml", null);
    }

    protected void seedSbsr(final String mndtReqId, final String status, final String reason) {
        ManLegFixture.seedLeg(jdbc, "man_sbsr_resp", mndtReqId, status, reason, mndtReqId + "_SBSR.xml", null);
    }

    protected void seedPbsr(final String mndtReqId, final String status, final String reason) {
        ManLegFixture.seedLeg(jdbc, "man_pbsr_resp", mndtReqId, status, reason, mndtReqId + "_PBSR.xml", null);
    }

    /** Delayed-authentication shape: a second PBSR under a distinct file at an explicit arrival time. */
    protected void seedPbsrAt(final String mndtReqId, final String status, final String reason,
                              final String responseFile, final String createdAt) {
        ManLegFixture.seedLeg(jdbc, "man_pbsr_resp", mndtReqId, status, reason, responseFile, createdAt);
    }

    /** The derived state of the entry carrying this mandate request id. */
    protected String stateOf(final String mndtReqId) {
        return jdbc.queryForObject("SELECT state FROM mandate_effective_status"
                + " WHERE mndt_req_id = ?", String.class, mndtReqId);
    }

    protected String reasonOf(final String mndtReqId) {
        return jdbc.queryForObject("SELECT reason FROM mandate_effective_status"
                + " WHERE mndt_req_id = ?", String.class, mndtReqId);
    }

    /** Override rows standing for ONE mandate, across every source. */
    protected int overrideCountOf(final String mandateRef) {
        final Integer n = jdbc.queryForObject("SELECT count(*) FROM mandate_override"
                + " WHERE mandate_ref = ?", Integer.class, mandateRef);
        return n == null ? -1 : n;
    }

    /** Rows a view yields for ONE mandate. Scoped by mandate_ref: the suite shares a container. */
    protected int rowCountOf(final String view, final String mandateRef) {
        final Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM %s WHERE mandate_ref = ?".formatted(view), Integer.class, mandateRef);
        return n == null ? -1 : n;
    }

    protected Map<String, Object> queryOne(final String sql) {
        return jdbc.queryForMap(sql);
    }

    /** CRDB returns INT8, so a rank read back through JDBC is a Long; normalise for assertions. */
    protected int intOf(final Map<String, Object> row, final String column) {
        return ((Number) row.get(column)).intValue();
    }
}
