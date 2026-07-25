package za.co.fnb.dcre.mrg.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-91: mnd_ext_status is the mandate mirror of collections ext_tx_status. It is a
 * PURE precedence view (PBSR &gt; SBSR &gt; ISR) over the three leg tables, with rows sourced
 * from the MRR request spine. No FSM here: that is mandate_effective_status (Task 5).
 *
 * <p>The pick grain is (mndt_req_id), NOT the collections (emission_id, e2e): a pain.012
 * message carries exactly ONE mandate, so there is no ISR fan-out to partition.</p>
 */
class MndExtStatusIT extends AbstractMrgCrdbIT {

    @Test
    void isrAloneSurfacesAsStageRankTwo() {
        seedSpine("CL01", "MND-1", "MREQ-1");
        seedIsr("MREQ-1", "ACCP", null);

        final var row = queryOne("SELECT raw_status, stage_rank, reason FROM mnd_ext_status"
                + " WHERE mndt_req_id='MREQ-1'");

        assertThat(row.get("raw_status")).isEqualTo("ACCP");
        assertThat(intOf(row, "stage_rank")).isEqualTo(2);
        assertThat(row.get("reason")).isNull();
    }

    @Test
    void pbsrOutranksSbsrAndIsr() {
        seedSpine("CL01", "MND-2", "MREQ-2");
        seedIsr("MREQ-2", "ACCP", null);
        seedSbsr("MREQ-2", "PART", null);
        seedPbsr("MREQ-2", "RJCT", "AC04");

        final var row = queryOne("SELECT raw_status, stage_rank, reason FROM mnd_ext_status"
                + " WHERE mndt_req_id='MREQ-2'");

        assertThat(row.get("raw_status")).isEqualTo("RJCT");
        assertThat(intOf(row, "stage_rank")).isEqualTo(4);
        assertThat(row.get("reason")).isEqualTo("AC04");
    }

    @Test
    void theLatestRowWinsWithinOneLeg() {
        seedSpine("CL01", "MND-3", "MREQ-3");
        seedPbsrAt("MREQ-3", "PDNG", null, "REPLY-3_PBSR.xml", "2026-07-25T09:00:00Z");
        seedPbsrAt("MREQ-3", "ACCP", null, "REPLY-3-AUTH_PBSR.xml", "2026-07-25T11:00:00Z");

        assertThat(queryOne("SELECT raw_status FROM mnd_ext_status WHERE mndt_req_id='MREQ-3'")
                .get("raw_status")).isEqualTo("ACCP");
    }

    @Test
    void aSpineEntryWithNoRepliesReadsPending() {
        seedSpine("CL01", "MND-4", "MREQ-4");

        final var row = queryOne("SELECT raw_status, stage_rank FROM mnd_ext_status"
                + " WHERE mndt_req_id='MREQ-4'");

        assertThat(row.get("raw_status")).isEqualTo("PDNG");
        assertThat(intOf(row, "stage_rank")).isEqualTo(1);
    }

    @Test
    void theClientDimensionComesFromTheSpineHeader() {
        seedSpine("CL07", "MND-5", "MREQ-5");
        seedPbsr("MREQ-5", "ACCP", null);

        assertThat(queryOne("SELECT client FROM mnd_ext_status WHERE mndt_req_id='MREQ-5'")
                .get("client")).isEqualTo("CL07");
    }

    @Test
    void everyMandateAttributeIsProjectedFromTheSpineEntry() {
        seedSpineWithDates("CL01", "MND-6", "MREQ-6", "20260201", "20270201");
        seedIsr("MREQ-6", "ACCP", null);

        final var row = queryOne("SELECT mandate_ref, contract_ref, debtor_account, creditor_account,"
                + " max_collection_amount, start_date, expiry_date FROM mnd_ext_status"
                + " WHERE mndt_req_id='MREQ-6'");

        assertThat(row.get("mandate_ref")).isEqualTo("MND-6");
        assertThat(row.get("contract_ref")).isEqualTo("CTRMND-6");
        assertThat(row.get("debtor_account")).isEqualTo("62000000020");
        assertThat(row.get("creditor_account")).isEqualTo("62000000010");
        // start_date/expiry_date stay VARCHAR(8) CCYYMMDD here: the cast belongs to Task 5.
        assertThat(row.get("start_date")).isEqualTo("20260201");
        assertThat(row.get("expiry_date")).isEqualTo("20270201");
    }
}
