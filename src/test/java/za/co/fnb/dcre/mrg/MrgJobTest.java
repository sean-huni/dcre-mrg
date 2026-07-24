package za.co.fnb.dcre.mrg;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange",
        "DCRE_EXCHANGE_ROOT=build/test-exchange"})
class MrgJobTest {

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
    Job mrgJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    void seedMandate(final String client, final String mandateRef, final String state) {
        final UUID arrival = UUID.randomUUID();
        jdbc.update("INSERT INTO mandate_request_header (arrival_id, msg_id_raw, msg_id, created_ts,"
                        + " entry_count, destination_id, business_date, client_token, layout_version)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                arrival, "MSG" + mandateRef, "MSG" + mandateRef, "20260722080000", 1, "ONHOST",
                "20260722", client, 1);
        jdbc.update("INSERT INTO mandate_request_entry (arrival_id, sequence, record_type, action_code,"
                        + " mandate_ref, currency, max_collection_amount_raw, max_collection_amount)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                arrival, 1, "MD", "CREATE", mandateRef, "ZAR", "1000", 10.00);
        jdbc.update("INSERT INTO mandate (mandate_ref, contract_ref, creditor_account, state)"
                + " VALUES (?,?,?,?)", mandateRef, "CTR" + mandateRef, "62000000010", state);
    }

    JobParameters window(final String client, final String window) {
        return new JobParametersBuilder()
                .addString("client", client, true)
                .addString("window", window, true)
                .toJobParameters();
    }

    List<String> mndLines(final Path file) throws Exception {
        return Files.readAllLines(file).stream().filter(l -> l.startsWith("MND|")).toList();
    }

    @Test
    void emitsStateDeltaReportPerClockWindow() throws Exception {
        final String client = "FNBRF01";
        seedMandate(client, "RFM001", "PDNG");
        seedMandate(client, "RFM002", "ACCP");
        final Path dir = Path.of("build/test-exchange/fnbrf01/onhost-resp-man/out");
        Files.deleteIfExists(dir.resolve(client + "_MSD_w1.txt"));
        Files.deleteIfExists(dir.resolve(client + "_MSD_w2.txt"));

        // (a) w1: first delta, both mandates reported.
        final JobExecution w1 = jobOperator.start(mrgJob, window(client, "w1"));
        assertEquals(BatchStatus.COMPLETED, w1.getStatus());
        final Path file1 = dir.resolve(client + "_MSD_w1.txt");
        assertEquals("MSD|" + client + "|w1", Files.readAllLines(file1).getFirst());
        assertEquals(List.of("MND|RFM001|PDNG", "MND|RFM002|ACCP"), mndLines(file1));
        assertTrue(Files.readAllLines(file1).contains("END|2"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM man_watermark WHERE client=?",
                Integer.class, client));

        // (b) w2: nothing moved -> zero-delta heartbeat, watermark untouched.
        final JobExecution w2 = jobOperator.start(mrgJob, window(client, "w2"));
        assertEquals(BatchStatus.COMPLETED, w2.getStatus());
        assertEquals(List.of("MSD|" + client + "|w2", "HB|" + "DCRE".concat("0".repeat(29)), "END|0"),
                Files.readAllLines(dir.resolve(client + "_MSD_w2.txt")));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM man_watermark WHERE client=?",
                Integer.class, client));
    }

    /**
     * Fail-closed: an unconfigured client makes the layout throw rather than emit to a
     * shared/wrong directory, so the batch job fails closed.
     */
    @Test
    void failsClosedWhenClientHasNoConfiguredExchangeDir() throws Exception {
        final String client = "FNBZZ99"; // absent from dcre-exchange-layout.yml
        seedMandate(client, "ZZM001", "PDNG");

        final JobExecution failed = jobOperator.start(mrgJob, window(client, "w1"));

        assertEquals(BatchStatus.FAILED, failed.getStatus(), "unconfigured client must fail closed");
        assertTrue(failed.getAllFailureExceptions().stream()
                        .anyMatch(t -> t instanceof IllegalArgumentException && t.getMessage().contains(client)),
                "failure is the fail-closed IllegalArgumentException naming the unconfigured client");
        assertFalse(Files.exists(Path.of("build/test-exchange/fnbzz99/onhost-resp-man/out/"
                + client + "_MSD_w1.txt")), "no report file is written for an unconfigured client");
    }
}
