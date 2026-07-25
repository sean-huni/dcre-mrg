package za.co.fnb.dcre.mrg.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.mrg.data.repo.CollectionOutcomeDao;

import java.util.List;

/**
 * The consecutive-terminal-failure test, ported verbatim from MSR's
 * MandateSuspensionService (SCRUM-91). Split out of the sweep service so the service keeps
 * one responsibility (decide and write) and this keeps the other (count), and so the
 * threshold is read in exactly one place.
 */
@Component
class SuspensionStreak {

    private final CollectionOutcomeDao outcomes;
    private final int suspendAfter;

    /**
     * The property key is DELIBERATELY still {@code dcre.msr.suspend-after}, carried over
     * with the sweep so the operational contract does not change under whoever tuned it.
     * Verified 2026-07-25: infra/dcre-infra references neither DCRE_MSR_SUSPEND_AFTER nor
     * any msrSuspendJob launch, so nothing external is pinned to the name today and a
     * rename is free whenever the register decides to make it (plan Task 10).
     */
    SuspensionStreak(final CollectionOutcomeDao outcomes,
                     @Value("${dcre.msr.suspend-after:3}") final int suspendAfter) {
        this.outcomes = outcomes;
        this.suspendAfter = suspendAfter;
    }

    /** True when the most recent {@code suspendAfter} collection outcomes are ALL terminal-failed. */
    boolean reached(final String mandateRef) {
        final List<Boolean> recent = outcomes.recentTerminalFailures(mandateRef, suspendAfter);
        return recent.size() >= suspendAfter && recent.stream().allMatch(Boolean.TRUE::equals);
    }
}
