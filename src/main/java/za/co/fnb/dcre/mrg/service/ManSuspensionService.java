package za.co.fnb.dcre.mrg.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fnb.dcre.mrg.data.repo.ManEffectiveStatusRepo;
import za.co.fnb.dcre.mrg.data.repo.ManOverrideRepo;

import java.util.Objects;

/**
 * The suspension sweep, absorbed from msrSuspendJob (SCRUM-91). It reads the collections DB
 * (dcre_col) READ-ONLY over the second datasource and suspends an effectively-active mandate
 * whose most recent {@code dcre.msr.suspend-after} collection outcomes are ALL terminal
 * failures (MS03). It writes ONLY dcre_man, and only the narrow mandate_override record.
 *
 * <p>The counting logic is MSR's, unchanged. What changed is the write target: MSR flipped a
 * state column on the mandate projection, which no longer exists. This is the ONE surviving
 * writer in the mandate response leg, and it exists only because a CockroachDB view cannot
 * span databases. Un-suspend stays a manual SQL runbook (v1).</p>
 *
 * <p>Each suspension is its own REQUIRES_NEW slice under a bounded 40001 retry (an aborted
 * CRDB transaction rejects every further statement, so a retry MUST get a fresh one). The
 * full-identity guarded UPSERT makes the sweep idempotent and resumable: a kill mid-sweep
 * loses no committed suspension and a restart re-writes none of them.</p>
 */
@Service
public class ManSuspensionService {

    /** The source token on every override this sweep writes; the other half of the guard identity. */
    static final String SOURCE = "COLLECTION_FAILURE";

    private static final String SUSPENDED = "SUSPENDED";
    private static final String MS03 = "MS03";
    private static final Logger log = LoggerFactory.getLogger(ManSuspensionService.class);

    private final SuspensionStreak streak;
    private final ManEffectiveStatusRepo mandates;
    private final ManOverrideRepo overrides;
    private final TransactionTemplate itemTx;

    public ManSuspensionService(final SuspensionStreak streak, final ManEffectiveStatusRepo mandates,
                                final ManOverrideRepo overrides,
                                final PlatformTransactionManager txManager) {
        this.streak = streak;
        this.mandates = mandates;
        this.overrides = overrides;
        this.itemTx = new TransactionTemplate(txManager);
        this.itemTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @return mandates newly suspended by this sweep; an override that already stood counts 0. */
    public int sweep() {
        int suspended = 0;
        for (final String ref : mandates.findActiveRefs()) {
            if (!streak.reached(ref)) {
                continue;
            }
            suspended += suspendOne(ref);
        }
        log.info("sweep stage=MRG type=SUSPEND suspended={}", suspended);
        return suspended;
    }

    private int suspendOne(final String mandateRef) {
        return Objects.requireNonNullElse(
                CrdbRetry.call("suspend ref=%s".formatted(mandateRef),
                        () -> itemTx.execute(status ->
                                overrides.upsertGuarded(mandateRef, SUSPENDED, MS03, SOURCE))),
                0);
    }
}
