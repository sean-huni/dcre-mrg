package za.co.fnb.dcre.mrg.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Declares a bean that exists ONLY on the suspension-sweep launch, never on a report window.
 *
 * <p>{@code Stage.MRG} serves two launches: the clock-window state-delta report ({@code mrgJob},
 * the yml default) and the sweep AGT selects with {@code DCRE_MRG_JOB_NAME=mrgSuspendJob}. Only
 * the sweep reads the collections DB, so AGT injects {@code DCRE_COL_DB_URL} launch-scoped onto
 * that launch alone (AGT commit 5839032) rather than stage-keyed, which would hand the
 * collections URL to pods that have no business with it.</p>
 *
 * <p>This annotation is the bean-side half of that decision. Without it the collections
 * datasource, its DAO and the whole sweep chain were UNCONDITIONAL, so every report-window pod
 * instantiated them and {@code ColDatasourceConfig}'s in-cluster guard fired correctly on a pod
 * that was deliberately never given the variable: every MRG report window failed, one per
 * minute, from 17:40:41Z on 2026-07-26. Bean scope now matches env scope, which is the fix; the
 * guard is untouched, because the guard is what surfaced this.</p>
 *
 * <p>{@link #SUSPEND_JOB} is the single pivot: it is both the gate value and the name
 * {@code MrgJobConfig} builds the sweep job under, so the launch selector and the beans that
 * launch needs cannot drift apart.</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = OnSuspendSweep.JOB_NAME, havingValue = OnSuspendSweep.SUSPEND_JOB)
public @interface OnSuspendSweep {

    /** Boot's Batch launch selector; MRG binds it to {@code DCRE_MRG_JOB_NAME} in the yml. */
    String JOB_NAME = "spring.batch.job.name";

    /** The sweep launch's job name: the one launch AGT wires the collections URL onto. */
    String SUSPEND_JOB = "mrgSuspendJob";
}
