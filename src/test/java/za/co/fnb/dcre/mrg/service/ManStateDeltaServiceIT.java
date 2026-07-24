package za.co.fnb.dcre.mrg.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;
import za.co.fnb.dcre.mrg.data.model.ManReportEntity;
import za.co.fnb.dcre.mrg.data.repo.ManReportRepo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mandate state-delta reporting proofs against a real CRDB (plan T14):
 * (1) a first window reports every mandate whose projection state moved past
 *     its watermark, ordered by mandate_ref, with the watermark + delivery
 *     ledger advanced exactly once each;
 * (2) an unchanged window re-reports nothing (zero-delta heartbeat, watermark
 *     untouched) and a single state flip reports exactly that one mandate;
 * (3) a quiet client emits the zero-delta heartbeat (END|0), never touching the
 *     watermark or ledger, and a same-window restart is an R-24 no-op;
 * (4) replay-by-report-id re-emits the SAME single file deterministically from
 *     the delivery ledger;
 * (5) kill-resume: a standing file is advanced EXACTLY as written (the late
 *     delta rides the next window), and re-running a window is a zero-dup no-op
 *     (count == count(distinct full identity) for ledger + watermark).
 * Clients come from the FNBT isolation pool + the canonical mandate clients.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.mrg.slice-size=10",
        "dcre.exchange-root=build/test-exchange", "DCRE_EXCHANGE_ROOT=build/test-exchange"})
class ManStateDeltaServiceIT {

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
    ManReportService service;

    @Autowired
    ManReplayService replay;

    @Autowired
    ManReportRepo reports;

    @Autowired
    JdbcTemplate jdbc;

    /** Seeds one mandate projection row plus the spine header/entry that carry the client dimension. */
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

    void flip(final String mandateRef, final String state) {
        jdbc.update("UPDATE mandate SET state=?, updated_at=now() WHERE mandate_ref=?", state, mandateRef);
    }

    String ref(final String client, final int i) {
        return "%sM%03d".formatted(client, i);
    }

    Path target(final String client, final String window) {
        return Path.of("build/test-exchange", client.toLowerCase(), "onhost-resp-man", "out",
                client + "_MSD_" + window + ".txt");
    }

    void cleanExchange(final String client, final String... windows) throws Exception {
        for (final String window : windows) {
            Files.deleteIfExists(target(client, window));
            Files.deleteIfExists(target(client, window).resolveSibling(
                    client + "_MSD_" + window + ".txt.tmp"));
        }
    }

    long watermarkCount(final String client) {
        final Long n = jdbc.queryForObject("SELECT count(*) FROM man_watermark WHERE client=?",
                Long.class, client);
        return n == null ? -1 : n;
    }

    // --- (1) first window reports every state-delta, advances watermark + ledger exactly once ---

    @Test
    void firstWindowReportsEveryMandateStateDelta() throws Exception {
        final String client = "FNBT15";
        cleanExchange(client, "w1");
        seedMandate(client, ref(client, 1), "PDNG");
        seedMandate(client, ref(client, 2), "ACCP");
        seedMandate(client, ref(client, 3), "CANC");

        final Optional<Path> emitted = service.window(client, "w1", false);

        assertThat(emitted).isPresent();
        assertThat(Files.readAllLines(emitted.get())).containsExactly(
                "MSD|" + client + "|w1",
                "MND|" + ref(client, 1) + "|PDNG",
                "MND|" + ref(client, 2) + "|ACCP",
                "MND|" + ref(client, 3) + "|CANC",
                "END|3");
        assertThat(watermarkCount(client)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM man_delivery_ledger l"
                        + " JOIN man_report r ON r.id = l.report_id WHERE r.client=?", Long.class, client))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT report_type FROM man_report WHERE file_name=?",
                String.class, client + "_MSD_w1.txt")).isEqualTo("SCHEDULED");
    }

    // --- (2) unchanged state is not re-reported; a single flip reports exactly that mandate ---

    @Test
    void unchangedStateIsNotReReportedAndAFlipReportsExactlyOneMandate() throws Exception {
        final String client = "FNBT14";
        cleanExchange(client, "w1", "w2", "w3");
        seedMandate(client, ref(client, 1), "PDNG");
        seedMandate(client, ref(client, 2), "PDNG");

        service.window(client, "w1", false); // both PDNG reported, watermark advanced

        // w2: nothing moved -> zero-delta heartbeat, watermark untouched
        final Optional<Path> w2 = service.window(client, "w2", false);
        assertThat(Files.readAllLines(w2.get())).containsExactly(
                "MSD|" + client + "|w2", "HB|" + "DCRE".concat("0".repeat(29)), "END|0");
        assertThat(watermarkCount(client)).isEqualTo(2L);

        // w3: one mandate advances PDNG -> ACCP; only that row is reported
        flip(ref(client, 1), "ACCP");
        final Optional<Path> w3 = service.window(client, "w3", false);
        assertThat(Files.readAllLines(w3.get())).containsExactly(
                "MSD|" + client + "|w3", "MND|" + ref(client, 1) + "|ACCP", "END|1");
        assertThat(jdbc.queryForObject("SELECT last_state FROM man_watermark WHERE client=? AND mandate_ref=?",
                String.class, client, ref(client, 1))).isEqualTo("ACCP");
    }

    // --- (3) zero-delta heartbeat on a quiet client; restart is an R-24 no-op ---

    @Test
    void quietClientEmitsZeroDeltaHeartbeatAndRestartIsNoOp() throws Exception {
        final String client = "FNBCC02";
        cleanExchange(client, "q1");

        final Optional<Path> hb = service.window(client, "q1", false);

        assertThat(hb).isPresent();
        assertThat(Files.readAllLines(hb.get())).containsExactly(
                "MSD|" + client + "|q1", "HB|" + "DCRE".concat("0".repeat(29)), "END|0");
        assertThat(jdbc.queryForObject("SELECT report_type FROM man_report WHERE file_name=?",
                String.class, client + "_MSD_q1.txt")).isEqualTo("HEARTBEAT");
        assertThat(watermarkCount(client)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM man_delivery_ledger l"
                        + " JOIN man_report r ON r.id=l.report_id WHERE r.client=?", Long.class, client))
                .isZero();

        // restart of the SAME window: R-24 no-op, same file, one registry row
        final Optional<Path> again = service.window(client, "q1", false);
        assertThat(again).contains(hb.get());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM man_report WHERE file_name=?",
                Long.class, client + "_MSD_q1.txt")).isEqualTo(1L);
    }

    // --- (4) replay-by-report-id re-emits the SAME single file deterministically from the ledger ---

    @Test
    void replayByReportIdReEmitsTheSameSingleFile() throws Exception {
        final String client = "FNBT13";
        cleanExchange(client, "r1");
        seedMandate(client, ref(client, 1), "ACCP");
        seedMandate(client, ref(client, 2), "PDNG");

        final Path original = service.window(client, "r1", false).orElseThrow();
        final List<String> originalLines = Files.readAllLines(original);
        final ManReportEntity report = reports.findByFileName(client + "_MSD_r1.txt").orElseThrow();

        // the OnHost file is lost; replay-by-report-id reproduces it deterministically
        Files.delete(original);
        final Path replayed = replay.replay(report.getId());

        assertThat(replayed).isEqualTo(original);
        assertThat(Files.readAllLines(replayed)).isEqualTo(originalLines);
        // exactly one registry row, ledger unchanged (no duplication on replay)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM man_report WHERE file_name=?",
                Long.class, client + "_MSD_r1.txt")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM man_delivery_ledger WHERE report_id=?",
                Long.class, report.getId())).isEqualTo(2L);
    }

    // --- (5) kill-resume: standing file advanced EXACTLY as written; re-run is zero-dup ---

    @Test
    void restartAdvancesExactlyTheStandingFileAndReRunIsZeroDup() throws Exception {
        final String client = "FNBT12";
        cleanExchange(client, "w1", "w2");
        seedMandate(client, ref(client, 1), "ACCP");
        seedMandate(client, ref(client, 2), "CANC");

        // Crash simulation: a prior run emitted a file (R-24: existing target = prior emission)
        // and died before any watermark advance. The sentinel line proves the replay advances the
        // FILE's content, never a fresh delta re-read (the ledger records what was reported).
        Files.createDirectories(target(client, "w1").getParent());
        final List<String> sentinel = List.of("MSD|" + client + "|w1", "MND|SENTINEL|PDNG", "END|1");
        Files.write(target(client, "w1"), sentinel);

        final Optional<Path> w1 = service.window(client, "w1", false);
        assertThat(w1).contains(target(client, "w1"));
        assertThat(Files.readAllLines(target(client, "w1"))).isEqualTo(sentinel);
        assertThat(jdbc.queryForList("SELECT concat(mandate_ref,'|',last_state) FROM man_watermark"
                + " WHERE client=?", String.class, client)).containsExactly("SENTINEL|PDNG");

        // the 2 real mandates were in NO emitted file -> they ride the next window
        final Optional<Path> w2 = service.window(client, "w2", false);
        assertThat(Files.readAllLines(w2.get())).containsExactly(
                "MSD|" + client + "|w2",
                "MND|" + ref(client, 1) + "|ACCP",
                "MND|" + ref(client, 2) + "|CANC",
                "END|2");

        // re-run BOTH windows: StagedWrite no-op + idempotent advance -> zero duplication
        service.window(client, "w1", false);
        service.window(client, "w2", false);

        // zero-dup audit: count(*) == count(distinct full business identity)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM man_watermark WHERE client=?",
                Long.class, client)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*)=count(DISTINCT concat(client,'|',mandate_ref,'|',state))"
                + " FROM man_delivery_ledger WHERE client=?", Boolean.class, client)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*)=count(DISTINCT file_name) FROM man_report"
                + " WHERE client=?", Boolean.class, client)).isTrue();
    }
}
