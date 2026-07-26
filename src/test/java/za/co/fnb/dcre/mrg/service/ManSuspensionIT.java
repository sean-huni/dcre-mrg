package za.co.fnb.dcre.mrg.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import za.co.fnb.dcre.mrg.data.AbstractMrgCrdbIT;
import za.co.fnb.dcre.mrg.data.ManColFixture;
import za.co.fnb.dcre.mrg.data.repo.ManOverrideRepo;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-91: the suspension writer moves from msrSuspendJob (which wrote the deleted
 * mandate projection) to MRG, writing a narrow mandate_override record instead. This is
 * the ONE writer in the mandate response leg, and it exists only because the signal lives
 * in dcre_col and a CockroachDB view cannot span databases.
 *
 * <p>dcre_col is a REAL second database in the same cluster, reached over the second
 * read-only datasource, so the cross-database constraint is exercised rather than
 * assumed away by co-locating the tables.</p>
 */
class ManSuspensionIT extends AbstractMrgCrdbIT {

    static final JdbcTemplate COL_JDBC;

    static {
        exec("CREATE DATABASE IF NOT EXISTS dcre_col");
        COL_JDBC = new JdbcTemplate(
                new DriverManagerDataSource(colUrl(), CRDB.getUsername(), CRDB.getPassword()));
    }

    static String colUrl() {
        return CRDB.getJdbcUrl().replaceFirst("(jdbc:postgresql://[^/]+/)[^?]+", "$1dcre_col");
    }

    static void exec(final String sql) {
        try (Connection c = java.sql.DriverManager.getConnection(
                CRDB.getJdbcUrl(), CRDB.getUsername(), CRDB.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void colProps(final DynamicPropertyRegistry registry) {
        registry.add("dcre.col-db-url", ManSuspensionIT::colUrl);
        registry.add("dcre.col-db-user", CRDB::getUsername);
        registry.add("dcre.col-db-password", CRDB::getPassword);
    }

    @Autowired
    ManSuspensionService service;

    @Autowired
    ManOverrideRepo overrides;

    @Autowired
    Job mrgSuspendJob;

    @Autowired
    JobOperator jobOperator;

    @BeforeEach
    void colSchema() {
        ManColFixture.createManCollectionOutcome(COL_JDBC);
    }

    @Test
    void threeConsecutiveTerminalFailuresSuspendTheMandate() {
        seedActive("CLS01", "MND-50", "MREQ-50");
        seedOutcomes("MND-50", "RJCT", "CANC", "RJCT");

        service.sweep();

        assertThat(stateOf("MREQ-50")).isEqualTo("SUSPENDED");
        assertThat(reasonOf("MREQ-50")).isEqualTo("MS03");
    }

    @Test
    void twoFailuresDoNotSuspend() {
        seedActive("CLS02", "MND-51", "MREQ-51");
        seedOutcomes("MND-51", "RJCT", "CANC");

        service.sweep();

        assertThat(stateOf("MREQ-51")).isEqualTo("ACCP");
    }

    @Test
    void aSuccessResetsTheConsecutiveRun() {
        seedActive("CLS03", "MND-52", "MREQ-52");
        seedOutcomes("MND-52", "RJCT", "ACSC", "RJCT", "CANC");

        service.sweep();

        assertThat(stateOf("MREQ-52")).isEqualTo("ACCP");
    }

    /**
     * Kill-resume shape: a second sweep must add no row. The guard that makes that true is
     * the FULL-identity ON CONFLICT (mandate_ref, source) plus its no-op WHERE, so the
     * repo is asserted directly rather than only through the candidate set (a candidate
     * set that has already moved off ACCP would pass for the wrong reason).
     */
    @Test
    void aRepeatSweepIsAZeroDuplicateNoOp() {
        seedActive("CLS04", "MND-53", "MREQ-53");
        seedOutcomes("MND-53", "RJCT", "RJCT", "RJCT");

        service.sweep();
        service.sweep();

        assertThat(overrideCountOf("MND-53")).isEqualTo(1);
        assertThat(overrides.upsertGuarded("MND-53", "SUSPENDED", "MS03", "COLLECTION_FAILURE")).isZero();
        assertThat(overrideCountOf("MND-53")).isEqualTo(1);
    }

    /**
     * The live case for the mandate_override_pick fan-out guard: a swept mandate that ops
     * later acts on carries TWO override rows, neither clobbering the other, and STILL
     * reads as exactly one row through the derived view and through CTV's contract.
     *
     * <p>The ops row is stamped relative to NOW, not at a literal instant. The sweep writes
     * its own row at the database's now(), so a hardcoded effective_from is a date bomb: it
     * won the pick until wall clock passed it and then silently lost, which is how this test
     * was found failing on the pristine tree on 2026-07-26 (fixed here, see the report).</p>
     */
    @Test
    void anOpsOverrideAndACollectionFailureCoexistAsOneEffectiveRow() {
        seedActive("CLS05", "MND-54", "MREQ-54");
        seedOutcomes("MND-54", "RJCT", "RJCT", "CANC");

        service.sweep();
        seedOverride("MND-54", "CANC", "MD07", "OPS", Instant.now().plusSeconds(60).toString());

        assertThat(overrideCountOf("MND-54")).isEqualTo(2);
        assertThat(rowCountOf("mandate_effective_status", "MND-54")).isEqualTo(1);
        assertThat(rowCountOf("man_ctv_view", "MND-54")).isEqualTo(1);
        assertThat(stateOf("MREQ-54")).isEqualTo("CANC");
    }

    /**
     * Candidate-set semantics, made explicit rather than accidental: an explicit override
     * beats the derived value (R-09), so an ops-pinned mandate is no longer effectively
     * ACCP and the sweep does not touch it. Ops keeps the last word until the record is
     * removed, exactly as MSR's projection-state guard behaved.
     */
    @Test
    void anExplicitlyOverriddenMandateIsNotASuspensionCandidate() {
        seedActive("CLS06", "MND-55", "MREQ-55");
        seedOverride("MND-55", "PDNG", "TM01", "OPS", "2026-07-24T08:00:00Z");
        seedOutcomes("MND-55", "RJCT", "RJCT", "RJCT");

        service.sweep();

        assertThat(overrideCountOf("MND-55")).isEqualTo(1);
        assertThat(stateOf("MREQ-55")).isEqualTo("PDNG");
    }

    /**
     * The wiring AGT actually launches. mrgSuspendJob is a second Job bean alongside mrgJob,
     * so this also proves the two-job context still resolves and that the thin tasklet
     * publishes the sweep count for the seam.
     */
    @Test
    void theSuspendJobRunsTheSweepAndPublishesTheCount() throws Exception {
        seedActive("CLS07", "MND-56", "MREQ-56");
        seedOutcomes("MND-56", "RJCT", "CANC", "RJCT");

        final JobExecution run = jobOperator.start(mrgSuspendJob,
                new JobParametersBuilder().addString("sweep", "S1", true).toJobParameters());

        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(run.getExecutionContext().getInt("suspended")).isEqualTo(1);
        assertThat(stateOf("MREQ-56")).isEqualTo("SUSPENDED");
    }

    /**
     * The grain defect in the sweep's candidate read (found by D5b). findActiveRefs asks "which
     * mandates are EFFECTIVELY active", a per-MANDATE question, and read the per-INSTRUCTION
     * view: a mandate whose CREATE was accepted stayed a candidate for ever, because an accepted
     * CANCEL freezes the MANDATE while the CREATE instruction's own row is still ACCP. The sweep
     * then wrote a SUSPENDED override onto a cancelled mandate, and since an explicit override
     * outranks everything derived (R-09) that resurrects a dead mandate into SUSPENDED, which is
     * the state the client is then told and the state CTV admits against.
     */
    @Test
    void aCancelledMandateIsNotASuspensionCandidate() {
        seedActive("CLS08", "MND-57", "MREQ-57A");
        seedInstruction("CLS08", "MND-57", "MREQ-57B", "CANCEL");
        seedPbsr("MREQ-57B", "ACCP", null);
        seedOutcomes("MND-57", "RJCT", "RJCT", "RJCT");

        service.sweep();

        assertThat(overrideCountOf("MND-57")).isZero();
        assertThat(currentStateOf("MND-57")).isEqualTo("CANC");
    }

    /**
     * Same defect, the other terminal shape: MD07 (system_action TERMINATE_NOW, end customer
     * deceased) lands on a LATER instruction, so the CREATE row stays ACCP at instruction grain
     * while the MANDATE is terminated. A terminated mandate must never be suspended.
     */
    @Test
    void anMd07TerminatedMandateIsNotASuspensionCandidate() {
        seedActive("CLS09", "MND-58", "MREQ-58A");
        seedInstruction("CLS09", "MND-58", "MREQ-58B", "AMEND");
        seedPbsr("MREQ-58B", "RJCT", "MD07");
        seedOutcomes("MND-58", "RJCT", "RJCT", "RJCT");

        service.sweep();

        assertThat(overrideCountOf("MND-58")).isZero();
        assertThat(currentStateOf("MND-58")).isEqualTo("RJCT");
    }

    /**
     * INVERTED 2026-07-26. This case used to assert that a rejected AMEND rejects the
     * INSTRUCTION only and leaves the mandate a live suspension candidate. Sean ruled the
     * latest Fintegrate Tx response is the true response, which is also what MSR's FSM did
     * (MandateStateMachine.finalLeg returns RJCT on isReject() with no action_code test, and
     * ACCP is not terminal), so a rejected AMEND is mandate-terminal. A terminal mandate is
     * not a suspension candidate: the sweep must leave it alone exactly as it leaves a
     * cancelled or MD07-terminated one alone.
     *
     * <p>The other half of the original coverage stands unchanged: the sweep reads the
     * per-MANDATE collapse, never a single instruction, so the CREATE instruction sitting at
     * ACCP does not make this mandate a candidate.</p>
     */
    @Test
    void aMandateWithARejectedAmendIsNoLongerASuspensionCandidate() {
        seedActive("CLS10", "MND-59", "MREQ-59A");
        seedInstruction("CLS10", "MND-59", "MREQ-59B", "AMEND");
        seedPbsr("MREQ-59B", "RJCT", "MD01");
        seedOutcomes("MND-59", "RJCT", "RJCT", "RJCT");

        service.sweep();

        assertThat(stateOf("MREQ-59A")).isEqualTo("ACCP");
        assertThat(overrideCountOf("MND-59")).isZero();
        assertThat(currentStateOf("MND-59")).isEqualTo("RJCT");
    }

    private void seedActive(final String client, final String mandateRef, final String mndtReqId) {
        seedSpine(client, mandateRef, mndtReqId);
        seedPbsr(mndtReqId, "ACCP", null);
    }

    /** Collection outcomes oldest first, one minute apart, so "most recent N" is unambiguous. */
    private void seedOutcomes(final String mandateRef, final String... statuses) {
        final Instant t0 = Instant.now().minus(Duration.ofDays(3));
        for (int i = 0; i < statuses.length; i++) {
            ManColFixture.insertCollectionOutcome(COL_JDBC, mandateRef, statuses[i],
                    t0.plusSeconds(60L * i));
        }
    }
}
