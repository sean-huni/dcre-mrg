package za.co.fnb.dcre.mrg.data;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared Testcontainers base for MRG's view-contract tests (SCRUM-91). MRG had NO shared
 * base class: MrgJobTest, ManStateDeltaServiceIT and bdd/CucumberSpringConfig each carry
 * their own static CockroachContainer plus @DynamicPropertySource. This factors that exact
 * bootstrap out once for the view suites, which all need the same real schema and the same
 * seed fixtures over the MRR request spine and the three response-leg tables.
 *
 * <p>Everything here seeds REAL tables through the real Liquibase-migrated schema: no mocks,
 * no hand-built view sources. The views under test are therefore proved against the shape the
 * owning services actually create.</p>
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false",
        "dcre.exchange-root=build/test-exchange", "DCRE_EXCHANGE_ROOT=build/test-exchange"})
public abstract class AbstractMrgCrdbIT {

    static final CockroachContainer CRDB =
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

    /** Seeds one MRR spine header + entry, the row source of the mandate view stack. */
    protected void seedSpine(final String client, final String mandateRef, final String mndtReqId) {
        seedSpineWithDates(client, mandateRef, mndtReqId, "20260101", "20991231");
    }

    /** Same spine seed with explicit CCYYMMDD start/expiry (VARCHAR(8), never DATE). */
    protected void seedSpineWithDates(final String client, final String mandateRef,
                                      final String mndtReqId, final String startDate,
                                      final String expiryDate) {
        final UUID arrival = UUID.randomUUID();
        jdbc.update("INSERT INTO mandate_request_header (arrival_id, msg_id_raw, msg_id, created_ts,"
                        + " entry_count, destination_id, business_date, client_token, layout_version)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                arrival, "MSG" + mndtReqId, "MSG" + mndtReqId, "20260725080000", 1, "ONHOST",
                "20260725", client, 1);
        jdbc.update("INSERT INTO mandate_request_entry (arrival_id, sequence, record_type, action_code,"
                        + " mandate_ref, contract_ref, creditor_account, debtor_account, currency,"
                        + " max_collection_amount_raw, max_collection_amount, start_date, expiry_date,"
                        + " mndt_req_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrival, 1, "MD", "CREATE", mandateRef, "CTR" + mandateRef, "62000000010",
                "62000000020", "ZAR", "1000", 10.00, startDate, expiryDate, mndtReqId);
    }

    protected void seedIsr(final String mndtReqId, final String status, final String reason) {
        seedLeg("man_isr_resp", mndtReqId, status, reason, mndtReqId + "_ISR.xml", null);
    }

    protected void seedSbsr(final String mndtReqId, final String status, final String reason) {
        seedLeg("man_sbsr_resp", mndtReqId, status, reason, mndtReqId + "_SBSR.xml", null);
    }

    protected void seedPbsr(final String mndtReqId, final String status, final String reason) {
        seedLeg("man_pbsr_resp", mndtReqId, status, reason, mndtReqId + "_PBSR.xml", null);
    }

    /** Delayed-authentication shape: a second PBSR under a distinct file at an explicit arrival time. */
    protected void seedPbsrAt(final String mndtReqId, final String status, final String reason,
                              final String responseFile, final String createdAt) {
        seedLeg("man_pbsr_resp", mndtReqId, status, reason, responseFile, createdAt);
    }

    private void seedLeg(final String table, final String mndtReqId, final String status,
                         final String reason, final String responseFile, final String createdAt) {
        final String columns = "(response_file, orgnl_msg_id, mndt_id, mndt_req_id, status, reason";
        if (createdAt == null) {
            jdbc.update("INSERT INTO %s %s) VALUES (?,?,?,?,?,?)".formatted(table, columns),
                    responseFile, "OUT-" + mndtReqId, mandateRefOf(mndtReqId), mndtReqId, status, reason);
            return;
        }
        jdbc.update("INSERT INTO %s %s, created_at) VALUES (?,?,?,?,?,?,?::TIMESTAMPTZ)".formatted(table, columns),
                responseFile, "OUT-" + mndtReqId, mandateRefOf(mndtReqId), mndtReqId, status, reason, createdAt);
    }

    /** MndtId on a reply is the mandate_ref; resolve it from the spine so fixtures stay real. */
    private String mandateRefOf(final String mndtReqId) {
        final List<String> refs = jdbc.queryForList(
                "SELECT mandate_ref FROM mandate_request_entry WHERE mndt_req_id = ?",
                String.class, mndtReqId);
        return refs.isEmpty() ? mndtReqId : refs.getFirst();
    }

    protected Map<String, Object> queryOne(final String sql) {
        return jdbc.queryForMap(sql);
    }

    /** CRDB returns INT8, so a rank read back through JDBC is a Long; normalise for assertions. */
    protected int intOf(final Map<String, Object> row, final String column) {
        return ((Number) row.get(column)).intValue();
    }
}
