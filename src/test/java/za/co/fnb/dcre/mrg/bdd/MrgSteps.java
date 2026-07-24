package za.co.fnb.dcre.mrg.bdd;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fnb.dcre.mrg.data.model.ManReportEntity;
import za.co.fnb.dcre.mrg.data.repo.ManReportRepo;
import za.co.fnb.dcre.mrg.domain.ManReportLayout;
import za.co.fnb.dcre.mrg.service.ManReplayService;
import za.co.fnb.dcre.mrg.service.ManReportService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Glue for the MRG mandate state-delta feature (positive + replay/heartbeat scenarios). */
public class MrgSteps {

    private static final AtomicInteger POOL = new AtomicInteger();

    @Autowired
    ManReportService service;

    @Autowired
    ManReplayService replay;

    @Autowired
    ManReportRepo reports;

    @Autowired
    JdbcTemplate jdbc;

    private String client;
    private List<String> watermarkSnapshot;
    private List<String> originalLines;
    private Path replayed;

    @Given("a mandate-capable MRG client")
    public void aMandateCapableClient() throws Exception {
        client = "FNBT%02d".formatted(POOL.getAndIncrement() % 12);
        final Path out = Path.of("build/test-exchange", client.toLowerCase(), "onhost-resp-man", "out");
        if (Files.isDirectory(out)) {
            try (Stream<Path> files = Files.list(out)) {
                for (final Path f : files.toList()) {
                    Files.deleteIfExists(f);
                }
            }
        }
        jdbc.update("DELETE FROM man_watermark WHERE client=?", client);
    }

    @Given("mandate {string} has projection state {string}")
    public void mandateHasState(final String mandate, final String state) {
        final String ref = ref(mandate);
        final var arrival = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO mandate_request_header (arrival_id, msg_id_raw, msg_id, created_ts,"
                        + " entry_count, destination_id, business_date, client_token, layout_version)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                arrival, "MSG" + ref, "MSG" + ref, "20260722080000", 1, "ONHOST", "20260722", client, 1);
        jdbc.update("INSERT INTO mandate_request_entry (arrival_id, sequence, record_type, action_code,"
                        + " mandate_ref, currency, max_collection_amount_raw, max_collection_amount)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                arrival, 1, "MD", "CREATE", ref, "ZAR", "1000", 10.00);
        jdbc.update("INSERT INTO mandate (mandate_ref, contract_ref, creditor_account, state)"
                + " VALUES (?,?,?,?)", ref, "CTR" + ref, "62000000010", state);
    }

    @When("mandate {string} advances to state {string}")
    public void mandateAdvances(final String mandate, final String state) {
        jdbc.update("UPDATE mandate SET state=?, updated_at=now() WHERE mandate_ref=?", state, ref(mandate));
    }

    @Given("the MRG window {string} has already run")
    public void windowAlreadyRun(final String window) throws Exception {
        service.window(client, window, false);
        watermarkSnapshot = watermark();
    }

    @When("the MRG window {string} runs")
    public void windowRuns(final String window) throws Exception {
        service.window(client, window, false);
    }

    @When("the MRG window {string} runs as a resend")
    public void windowRunsResend(final String window) throws Exception {
        service.window(client, window, true);
    }

    @When("the window {string} report is lost and replayed by its report id")
    public void reportLostAndReplayed(final String window) throws Exception {
        final ManReportEntity report = reports.findByFileName(fileName(window)).orElseThrow();
        final Path original = out(window);
        originalLines = Files.readAllLines(original);
        Files.delete(original);
        replayed = replay.replay(report.getId());
    }

    @Then("the MRG report for window {string} lists exactly:")
    public void reportListsExactly(final String window, final DataTable table) throws Exception {
        final List<String> expected = new ArrayList<>();
        for (final Map<String, String> row : table.asMaps()) {
            expected.add(ManReportLayout.detail(ref(row.get("mandate")), row.get("state")));
        }
        final List<String> actual = Files.readAllLines(out(window)).stream()
                .filter(l -> l.startsWith(ManReportLayout.DETAIL_PREFIX)).toList();
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Then("the MRG report for window {string} is a zero-delta heartbeat")
    public void reportIsHeartbeat(final String window) throws Exception {
        assertThat(Files.readAllLines(out(window))).containsExactly(
                ManReportLayout.header(client, window), ManReportLayout.heartbeatLine(),
                ManReportLayout.trailer(0));
    }

    @Then("the client watermark is unchanged by the heartbeat")
    public void watermarkUnchanged() {
        assertThat(watermark()).isEqualTo(watermarkSnapshot);
    }

    @Then("the client watermark holds exactly:")
    public void watermarkHoldsExactly(final DataTable table) {
        final List<String> expected = new ArrayList<>();
        for (final Map<String, String> row : table.asMaps()) {
            expected.add(ref(row.get("mandate")) + "|" + row.get("state"));
        }
        assertThat(watermark()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Then("the replayed report is byte-identical to the original window {string} report")
    public void replayedIsIdentical(final String window) throws Exception {
        assertThat(replayed).isEqualTo(out(window));
        assertThat(Files.readAllLines(replayed)).isEqualTo(originalLines);
    }

    private List<String> watermark() {
        return jdbc.queryForList("SELECT concat(mandate_ref,'|',last_state) FROM man_watermark"
                + " WHERE client=? ORDER BY mandate_ref", String.class, client);
    }

    private String ref(final String mandate) {
        return client + mandate;
    }

    private String fileName(final String window) {
        return ManReportLayout.fileName(client, window);
    }

    private Path out(final String window) {
        return Path.of("build/test-exchange", client.toLowerCase(), "onhost-resp-man", "out",
                fileName(window));
    }
}
