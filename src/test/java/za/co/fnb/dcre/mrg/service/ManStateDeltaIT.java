package za.co.fnb.dcre.mrg.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import za.co.fnb.dcre.mrg.data.AbstractMrgCrdbIT;
import za.co.fnb.dcre.mrg.data.model.ManStateRow;
import za.co.fnb.dcre.mrg.data.repo.ManWatermarkRepo;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-91: MRG's delta source moves from man_ext_status (projection-dressing over the
 * MSR-written mandate table) to mandate_effective_status (derived). The watermark contract
 * is unchanged: report a mandate only when its state differs from the last reported one.
 *
 * <p>Each test owns its own client token. The suite shares one container, so a delta read
 * scoped to a shared client would see other tests' mandates and assert nothing.</p>
 */
class ManStateDeltaIT extends AbstractMrgCrdbIT {

    private static final int SLICE = 100;

    @Autowired
    ManWatermarkRepo watermarks;

    @Test
    void aNewlyActiveMandateIsADelta() {
        seedSpine("CLD01", "MND-40", "MREQ-40");
        seedPbsr("MREQ-40", "ACCP", null);

        assertThat(deltaRefs("CLD01")).containsExactly("MND-40");
    }

    @Test
    void anAlreadyReportedStateIsNotADelta() {
        seedSpine("CLD02", "MND-41", "MREQ-41");
        seedPbsr("MREQ-41", "ACCP", null);
        seedWatermark("CLD02", "MND-41", "ACCP");

        assertThat(deltaRefs("CLD02")).isEmpty();
    }

    /**
     * The one signal a view cannot derive. A suspension lands as a mandate_override record,
     * so MRG only reports it if its delta reads the DERIVED view; man_ext_status projected
     * the written mandate table and had no override awareness at all.
     */
    @Test
    void aSuspensionOverrideIsADeltaAgainstThePreviousActiveState() {
        seedSpine("CLD03", "MND-42", "MREQ-42");
        seedPbsr("MREQ-42", "ACCP", null);
        seedWatermark("CLD03", "MND-42", "ACCP");
        seedOverride("MND-42", "SUSPENDED", "MS03", "COLLECTION_FAILURE", "2026-07-25T08:00:00Z");

        assertThat(deltaRefs("CLD03")).containsExactly("MND-42");
    }

    /** A resend ignores the watermark and re-reads every current state from the derived view. */
    @Test
    void aResendRangeSliceIgnoresTheWatermark() {
        seedSpine("CLD04", "MND-43", "MREQ-43");
        seedPbsr("MREQ-43", "ACCP", null);
        seedWatermark("CLD04", "MND-43", "ACCP");

        assertThat(watermarks.findRangeSlice("CLD04", "", SLICE))
                .extracting(ManStateRow::mandateRef).containsExactly("MND-43");
    }

    @Test
    void theRetiredProjectionViewIsGone() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.views WHERE table_name = 'man_ext_status'",
                Integer.class)).isZero();
    }

    private List<String> deltaRefs(final String client) {
        return watermarks.findDeltaSlice(client, "", SLICE).stream().map(ManStateRow::mandateRef).toList();
    }

    private void seedWatermark(final String client, final String mandateRef, final String state) {
        jdbc.update("INSERT INTO man_watermark (client, mandate_ref, last_state) VALUES (?,?,?)",
                client, mandateRef, state);
    }
}
