package za.co.fnb.dcre.mrg.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import za.co.fnb.dcre.mrg.data.AbstractMrgCrdbIT;
import za.co.fnb.dcre.mrg.data.ManLegFixture;
import za.co.fnb.dcre.mrg.data.repo.ManReportRepo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-91: the mandate transition audit trail, re-homed from MSR to MRG. MSR was the only
 * writer of mandate_status_history and its repo disappears with the projection, so the trail
 * is now derived: under derivation there is no transition EVENT, the transition IS the delta
 * between the watermark's last reported state and the currently derived state, which is
 * exactly what a report window already computes.
 *
 * <p>Each test owns its own client from the FNBT isolation pool: the suite shares one
 * container and man_watermark / man_report rows are client-global.</p>
 */
class ManStatusHistoryIT extends AbstractMrgCrdbIT {

    @Autowired
    ManReportService service;

    @Autowired
    ManDeliveryAdvancer advancer;

    @Autowired
    ManReportRepo reports;

    // --- (1) first observation: no watermark yet, so from_state is MSR's implicit start state ---

    @Test
    void aFirstObservationRecordsRequestedAsTheFromState() throws IOException {
        final String client = "FNBT01";
        final String ref = ref(client);
        cleanExchange(client, "h1");
        ManLegFixture.seedMandateInState(jdbc, client, ref, "ACCP");

        service.window(client, "h1", false);

        assertThat(historyOf(ref)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("from_state", "REQUESTED");
            assertThat(row).containsEntry("to_state", "ACCP");
        });
    }

    // --- (2) a moved state carries the PREVIOUS reported state, plus the derived reason ---

    @Test
    void aMovedStateCarriesThePreviousReportedStateAsFromState() throws IOException {
        final String client = "FNBT02";
        final String ref = ref(client);
        cleanExchange(client, "h1", "h2");
        ManLegFixture.seedMandateInState(jdbc, client, ref, "PDNG");
        service.window(client, "h1", false);

        ManLegFixture.seedLeg(jdbc, "man_pbsr_resp", ManLegFixture.reqIdOf(ref), "RJCT", "MD01",
                ref + "-RJCT_PBSR.xml", null);
        service.window(client, "h2", false);

        final List<Map<String, Object>> rows = historyOf(ref);
        assertThat(rows).hasSize(2);
        assertThat(rows.getLast()).containsEntry("from_state", "PDNG")
                .containsEntry("to_state", "RJCT")
                .containsEntry("reason_code", "MD01");
    }

    // --- (3) replaying the SAME report window is a zero-duplicate no-op ---

    /**
     * The kill-resume replay: a run that died mid-advance restarts and advances the SAME
     * emitted file under the SAME report id (man_report is find-or-save on file_name), so
     * every already-appended mandate must land on ON CONFLICT DO NOTHING. Driven through the
     * advancer rather than a second {@code window} call because once the watermark has moved
     * the window has no delta left and takes the heartbeat branch, which never advances.
     */
    @Test
    void replayingTheSameWindowWritesNoSecondRow() throws IOException {
        final String client = "FNBT03";
        final String ref = ref(client);
        cleanExchange(client, "h1");
        ManLegFixture.seedMandateInState(jdbc, client, ref, "ACCP");

        final Path emitted = service.window(client, "h1", false).orElseThrow();
        advancer.advanceFromEmittedFile(client, emitted, reports.findByFileName(
                emitted.getFileName().toString()).orElseThrow().getId());

        assertThat(historyOf(ref)).hasSize(1);
    }

    // --- (4) a DIFFERENT state in a LATER window is a new transition, so a new row ---

    @Test
    void aLaterWindowReachingADifferentStateAppendsANewRow() throws IOException {
        final String client = "FNBT04";
        final String ref = ref(client);
        cleanExchange(client, "h1", "h2");
        ManLegFixture.seedMandateInState(jdbc, client, ref, "PDNG");
        service.window(client, "h1", false);

        ManLegFixture.advanceTo(jdbc, ref, "ACCP");
        service.window(client, "h2", false);

        assertThat(historyOf(ref)).extracting(row -> row.get("to_state"))
                .containsExactly("PDNG", "ACCP");
    }

    // --- (5) a derived row has no leg and no response file: those exist only under MSR ---

    @Test
    void aDerivedRowCarriesNoSourceLegAndNoResponseFile() throws IOException {
        final String client = "FNBT05";
        final String ref = ref(client);
        cleanExchange(client, "h1");
        ManLegFixture.seedMandateInState(jdbc, client, ref, "ACCP");

        service.window(client, "h1", false);

        assertThat(historyOf(ref)).singleElement().satisfies(row -> {
            assertThat(row.get("source_leg")).isNull();
            assertThat(row.get("response_file")).isNull();
            assertThat(row.get("report_id")).isNotNull();
        });
    }

    private String ref(final String client) {
        return client + "M001";
    }

    private List<Map<String, Object>> historyOf(final String mandateRef) {
        return jdbc.queryForList("SELECT from_state, to_state, reason_code, source_leg,"
                + " response_file, report_id FROM mandate_status_history"
                + " WHERE mandate_ref = ? ORDER BY at", mandateRef);
    }

    private void cleanExchange(final String client, final String... windows) throws IOException {
        for (final String window : windows) {
            final Path target = Path.of("build/test-exchange", client.toLowerCase(),
                    "onhost-resp-man", "out", "%s_MSD_%s.txt".formatted(client, window));
            Files.deleteIfExists(target);
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".tmp"));
        }
    }
}
