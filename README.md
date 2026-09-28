# dcre-mrg

> Part of the DCRE fleet. For the fleet map, the rulings and the diagrams that specify every stage, start at the [DCRE design register](https://github.com/sean-huni/dcre-design-register); the complete list of live repositories is its [Repositories](https://github.com/sean-huni/dcre-design-register#repositories) table.

Mandates Report Generator: the clock-launched end of the DCRE mandates response flow. MRG owns the derived mandate state views in `dcre_man`, emits one mandate state-delta report per client per clock window to OnHost, and runs the suspension sweep that turns a streak of failed collections into a `SUSPENDED` override.

## What it does

**Position in the fleet.** Stage `MRG`, mandates family, RES leg, clock-launched. MRG is in no route DAG: AGT's `RouteDags` holds the mandates response route (`FINT_RESP_MAN`) as the three token-picked leg readers only, and MRG runs from two AGT clocks instead, `MrgScheduler` (one report window per mandate-capable client, `AGT_MAN_CLIENTS`) and `MrgSuspendScheduler` (one global suspension sweep per window). Upstream, through the tables it reads: `MIX`, `MSX` and `MPX` (the leg replies), `MRR` (the request spine), `MRV` (`man_validation_log`) and, for the sweep only, CRG's `man_collection_outcome` view in `dcre_col`. Downstream: OnHost, via the `onhost-resp-man/out` report file; CTV's collections mandate gate reads MRG's `man_ctv_view` (per AGT's `ctv-mandate-source` configuration, checked 2026-09-28). Diagram sheet: `dcre-mandates-res` in the design register.

MRG ships two Spring Batch jobs, each a single tasklet step, and AGT picks one per launch through `spring.batch.job.name` (bound to `DCRE_MRG_JOB_NAME`, default `mrgJob`).

**`mrgJob`: the state-delta report.** Job identity is `(client, window)`. Dispatch is on the non-identifying `report.type` parameter:

- `SCHEDULED` (default): reads the per-mandate `mandate_current_status` view for the client in keyset slices of `dcre.mrg.slice-size`, keeps the mandates whose state differs from `man_watermark.last_state` (or that were never reported), and writes `<CLIENT>_MSD_<window>.txt` to the client's `onhost-resp-man/out` exchange directory. `resend=true` ignores the watermark and re-reports every current mandate state.
- `REPLAY`: re-emits an existing report, addressed by `report.id`, from its `man_delivery_ledger` rows.

The file layout is a SYNTHETIC-CONTRACT (A-57 class; the legacy OnHost copybook is unrecovered): header `MSD|client|window`, one `MND|mandate_ref|state` per reported mandate, trailer `END|count`. A quiet window emits a zero-delta heartbeat (`MSD` header, one `HB|DCRE000...` placeholder line, `END|0`) so the consumer can tell "no movement" from "MRG dead".

Order follows R-29: the whole file becomes visible first (platform-files `StagedWrite`, temp plus `ATOMIC_MOVE`), then the watermark and ledger advance by streaming the emitted file back. The file is the only record of what was externally reported, so a mandate whose state moved after the file was written rides the next window. A crash between the two phases replays as skip-the-existing-file plus advance-from-file. A heartbeat that already stands for a window is never rewritten, and a late delta against it is deferred to the next window rather than ledgered against a zero-`MND` file.

**`mrgSuspendJob`: the suspension sweep.** Enumerates every mandate reading `ACCP` in `mandate_current_status` (overrides already applied), reads its most recent `dcre.msr.suspend-after` rows from `man_collection_outcome` in `dcre_col`, and when all of them are terminal failures writes a `mandate_override` row (`state=SUSPENDED`, `reason=MS03`, `source=COLLECTION_FAILURE`). It writes only `dcre_man`. Un-suspend is a manual SQL runbook (v1).

## Architecture and principles

Spring Boot 4.1.0 / Spring Batch 6 (metadata DDL is Spring Batch 6.0.4) / Java 25 on CockroachDB (PostgreSQL driver). An ephemeral batch process, not a server: platform-batch `ExitCodeMain` maps the Batch outcome to the JVM exit code.

- **Layer-first, 3-tier packages** (`config`, `service`, `domain`, `data/model`, `data/repo`). `ManReportTasklet` and `SuspensionTasklet` are thin adapters; the business tier is `ManReportService`, `ManStateDeltaWriter`, `ManDeliveryAdvancer`, `ManAdvanceRecorder`, `ManReplayService` and `ManSuspensionService`; SQL lives only in `data/repo`.
- **State is derived, not written.** There is no mandate projection table. `mnd_ext_status` ranks the leg replies (PBSR > SBSR > ISR), `mandate_effective_status` applies the mandate FSM per instruction, and `mandate_current_status` collapses those to one row per `mandate_ref` with order-independent aggregate predicates. The report delta and the sweep both read the per-mandate collapse, because the watermark is keyed per mandate.
- **One writer in the leg, by exception.** `mandate_override` exists only because the suspension signal lives in `dcre_col` and a CockroachDB view cannot span databases. It is marked `[!CONVENTION-OVERRIDE]` in `005-man-effective.xml`, with its retirement condition: once that signal is colocated in or projected into `dcre_man`, the override table and the sweep are deleted.
- **Full-identity idempotency.** Every write is an `INSERT ... ON CONFLICT` on the business key, never a CockroachDB `UPSERT` (which arbitrates on the PK only): watermark on `(client, mandate_ref)`, ledger on the partial unique index `(client, mandate_ref, state) WHERE manual_ref IS NULL`, history on `(mandate_ref, to_state, report_id)`, override on `(mandate_ref, source)`. Replaying a window or re-running a sweep is a zero-duplicate no-op.
- **40001 handling.** Each advance slice and each suspension runs in its own `REQUIRES_NEW` transaction under `CrdbRetry` (5 attempts, backoff), because an aborted CockroachDB transaction rejects every further statement. The primary Hikari pool runs `TRANSACTION_READ_COMMITTED` (SCRUM-90) to remove the uncatchable 40001 at the Batch chunk commit; MRG uses no `AS OF SYSTEM TIME`, so that is safe here.
- **Launch-scoped cross-database read.** The `dcre_col` datasource, its DAO and the whole sweep chain are declared only when `spring.batch.job.name=mrgSuspendJob` (`@OnSuspendSweep`). A report window therefore needs no `dcre_col` wiring. The datasource is built inline as a non-pooling `SimpleDriverDataSource`, never a `DataSource` bean, so Liquibase, Batch and Spring Data stay on `dcre_man`. If `KUBERNETES_SERVICE_HOST` is set and `DCRE_COL_DB_URL` is still the localhost default, context start fails naming the variable.
- **12FactorApp Alignment: https://12factor.net/**: configuration from the environment over committed working defaults in `application.yml`, so a clean clone runs with no `.env`; stateless one-shot process; the databases are attached resources.
- **Outcome seam and heartbeat.** Each job carries platform-batch `OutcomeSeamListener` (writes `BUSINESS_ACCEPTED` on `COMPLETED`, named by `JOB_NAME` or `local-mrg-<executionId>`) and `HeartbeatWriter`. `BatchMetaConfig` abandons stale `MRG_BATCH_` executions before the job runner fires (A-39a).

### Data

Database today: the shared mandates database `dcre_man` (primary datasource `DCRE_DB_URL`, `DCRE_DB_USER`, `DCRE_DB_PASSWORD`); `agt_ops` for the platform-batch heartbeat (`DCRE_AGTOPS_DB_*`); and, for `mrgSuspendJob` only, a read-only `dcre_col` datasource (`DCRE_COL_DB_URL`, `DCRE_COL_DB_USER`, `DCRE_COL_DB_PASSWORD`). MRG performs no `spine_state` transition; it pre-creates the spine tables but never writes their rows.

Liquibase owns the schema in the shared `dcre_man` (`db/changelog/db.changelog-master.xml`, calendar layout `2026/07/`), with per-service history tables `mrg_databasechangelog` / `mrg_databasechangeloglock`. The changelog is a v1 baseline (SCRUM-107). `MARK_RAN` guards appear only on objects that a second writer can create first on a fresh database; objects MRG alone creates carry none.

| Changelog | Objects | Ownership |
|---|---|---|
| `000-man-core-bootstrap.xml` | `account_type`, `account`, `mandate_reason_code` (seeded) | Shared core; guarded convergence with the dcre-infra seed and the other M-services |
| `001-man-spine-bootstrap.xml` | `mandate_request_header`, `mandate_request_entry` | MRR owns; MRG pre-creates (guarded) because it is clock-launched and may migrate first |
| `002-batch-metadata.xml` | `MRG_BATCH_*` | MRG; Spring Batch 6.0.4 DDL in typed XML, `EXIT_MESSAGE` widened to TEXT |
| `003-man-reporting.xml` | `man_report`, `man_delivery_ledger` (+ `uq_man_ledger_auto` partial unique index), `man_watermark` | MRG |
| `004-man-views.xml` | `man_isr_resp`, `man_sbsr_resp`, `man_pbsr_resp`, `man_validation_log` pre-creates and `ix_man_*_pick` indexes; views `mnd_isr_pick`, `mnd_sbsr_pick`, `mnd_pbsr_pick`, `mnd_ext_status` | Tables owned by MIX, MSX, MPX, MRV (guarded pre-creates); views MRG |
| `005-man-effective.xml` | `mandate_override`, `ix_mandate_override_pick`; views `mandate_override_pick`, `mandate_effective_status` | MRG |
| `007-man-current-status.xml` | views `mandate_current_status`, `man_ctv_view` (+ `SELECT` grant to role `ctv` when it exists) | MRG; `man_ctv_view` is the column contract CTV reads |
| `008-man-status-history.xml` | `mandate_status_history` | MRG |

Numbers 006 and 009 are deliberately unused so changeset identity stays stable.

Per advanced mandate, `ManAdvanceRecorder` writes three rows in one transaction and in this order: the `mandate_status_history` transition (from the watermark's last state, or `REQUESTED` on first observation), the `man_delivery_ledger` row, then the `man_watermark` advance. The history append must read the watermark before it moves.

The one read outside `dcre_man` is `SELECT is_terminal_failure FROM man_collection_outcome WHERE mandate_ref = ? ORDER BY occurred_at DESC LIMIT ?` against the CRG-owned view in `dcre_col` (A-70), over the sweep's read-only second datasource.

## Prerequisites

- Java 25 (`.sdkmanrc` pins `java=25-tem`; Gradle wrapper 9.5.1 included)
- Docker (Testcontainers CockroachDB `v26.2.3` for tests, image build for deployment)
- Platform libs in Maven Local: `za.co.fnb.dcre:platform-persistence:0.1.0`, `platform-files:0.1.0`, `platform-batch:0.1.0`
- A reachable CockroachDB for a real local run (defaults target `localhost:26257`, databases `dcre_man`, `agt_ops`, and `dcre_col` for the sweep)

## Quickstart

```bash
sdk env                 # Java 25 from .sdkmanrc

# 1) Publish the platform libs to Maven Local (once), from each platform repo clone:
./gradlew publishToMavenLocal

# 2) Build and test (Docker required; no .env needed, dev defaults are committed)
./gradlew test

# 3) One report window against a local CockroachDB
./gradlew bootJar       # build/libs/mrg-2.0.jar
java -jar build/libs/mrg-2.0.jar \
  'client=FNBCC01,java.lang.String,true' \
  'window=<window-key>,java.lang.String,true'

# 4) The suspension sweep
DCRE_MRG_JOB_NAME=mrgSuspendJob java -jar build/libs/mrg-2.0.jar \
  'window=<window-key>,java.lang.String,true'
```

Optional report parameters (non-identifying): `resend=true`, or `report.type=REPLAY` with `report.id=<uuid>`. `mrgSuspendJob` reads no parameter of its own; the identifying `window` exists only so each window starts a new job instance, which is how AGT launches it.

## Configuration

Committed defaults live in `application.yml`; any environment variable below overrides its default. The table lists what `application.yml` binds explicitly. It is not a closed set: Spring Boot relaxed binding lets any property (for example `spring.batch.job.name` as `SPRING_BATCH_JOB_NAME`, or `dcre.mrg.slice-size` as `DCRE_MRG_SLICESIZE`) be overridden by its derived environment variable name, and imported files (`classpath:dcre-exchange-layout.yml` from platform-batch) add their own properties.

| Env | Default | Purpose |
|---|---|---|
| `DCRE_MRG_JOB_NAME` | `mrgJob` | Job selector: `mrgJob` (report window) or `mrgSuspendJob` (suspension sweep) |
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_man?sslmode=disable` | Primary datasource: mandates DB, Liquibase and Batch metadata |
| `DCRE_DB_USER` | `root` | Primary DB user |
| `DCRE_DB_PASSWORD` | (empty) | Primary DB password |
| `DCRE_AGTOPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | Heartbeat datasource |
| `DCRE_AGTOPS_DB_USER` | `root` | Heartbeat DB user |
| `DCRE_AGTOPS_DB_PASSWORD` | (empty) | Heartbeat DB password |
| `DCRE_COL_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_col?sslmode=disable` | Read-only collections DB for the sweep; the default is fatal inside a pod |
| `DCRE_COL_DB_USER` | `root` | Collections DB user |
| `DCRE_COL_DB_PASSWORD` | (empty) | Collections DB password |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | Exchange root for the outcome seam |
| `DCRE_MSR_SUSPEND_AFTER` | `3` | Consecutive terminal-failed collections that suspend an `ACCP` mandate (key `dcre.msr.suspend-after`, historical name kept deliberately) |
| `DCRE_MRG_SLICE_SIZE` | `5000` | Keyset slice size for delta reads and advance transactions |
| `DCRE_AMOUNT_SCALE` | `2` | Bound as `dcre.amount-scale`; no class in this repository reads it |
| `JOB_NAME` | `local-mrg-<executionId>` | Set by AGT on the K8s Job; names the outcome seam file and `man_report.job_name` |
| `KUBERNETES_SERVICE_HOST` | (unset) | Set by kubelet; arms the in-cluster `DCRE_COL_DB_URL` guard |

Fixed in `application.yml`: Batch table prefix `MRG_BATCH_`, Liquibase history tables `mrg_databasechangelog` / `mrg_databasechangeloglock`, `TRANSACTION_READ_COMMITTED` on the primary pool, virtual threads on.

## Testing

```bash
./gradlew test   # needs Docker; runs JUnit, Testcontainers ITs and the Cucumber suite
./gradlew test --tests '*ManSuspensionIT'   # one suite
```

Suites run against Testcontainers CockroachDB, image pinned at `cockroachdb/cockroach:v26.2.3` (`AbstractMrgCrdbIT`, `MrgJobTest`, `ManStateDeltaServiceIT`, `CucumberSpringConfig`), with the real Liquibase-migrated schema; state is reached by seeding leg replies, never by writing a state.

- `MrgJobTest`: the real `mrgJob` through `JobOperator`; a window emits the state-delta report, and a client with no configured exchange directory fails closed.
- `ManStateDeltaServiceIT`, `ManStateDeltaIT`: delta per window, heartbeat, single-flip, replay by report id, kill-resume advance from the emitted file, per-mandate grain.
- `ManStatusHistoryIT`, `ManStatusHistoryShapeIT`: the derived transition audit trail and its v1 shape.
- `MndExtStatusIT`, `ManReplyPickIT`, `MandateEffectiveStatusIT`, `MandateCurrentStatusIT`: the view stack, leg precedence, pick determinism and the FSM collapse.
- `MndViewBootstrapIT`, `ManV1BaselineIT`, `ProjectionRemovalIT`: bootstrap pre-creates match the owners' shapes, the baseline carries no retrofit apparatus, no mandate projection exists.
- `ManSuspensionIT`, `CollectionOutcomeContractIT`: the sweep against a real second `dcre_col` database, and the `man_collection_outcome` read contract from the reader's side.
- `ColDatasourceConfigTest`, `ReportLaunchContextIT`: the in-cluster `DCRE_COL_DB_URL` guard, and a report-window context booting with no `dcre_col` wiring.
- `CucumberSuiteTest`: BDD scenarios in `src/test/resources/features/mandate-state-delta.feature` (tag `@mrg`).

## Local cluster deployment

```bash
./gradlew bootJar
docker build -t dcre-mrg:<version> .
kind load docker-image --name dcre-dev dcre-mrg:<version>
```

The image is `eclipse-temurin:25-jre-alpine` running `build/libs/mrg-2.0.jar`. AGT launches MRG as an ephemeral K8s Job on a clock, in the mandates flow namespace (`AGT_NAMESPACE_MAN`, default `dcre-man`), with the image from `AGT_MRG_IMAGE` (empty default, which leaves MRG launch-disabled). Report windows: every `AGT_MRG_INTERVAL_SECONDS` (default 60) per client token seen in AGT's arrivals that is also listed in `AGT_MAN_CLIENTS` (default `FNBCC01,FNBCC02,FNBRF01`), args `client=<CLIENT>` and `window=w<n>`. Suspension sweep: every `AGT_MRG_SUSPEND_INTERVAL_SECONDS` (default 60), arg `window=w<n>`, with `DCRE_MRG_JOB_NAME=mrgSuspendJob` and `DCRE_COL_DB_URL` (AGT's collections `AGT_SERVICE_DB_URL`) injected on that launch only. Every launch also receives `DCRE_DB_URL` from `AGT_MAN_SERVICE_DB_URL`, `DCRE_EXCHANGE_ROOT`, `JOB_NAME` and the `agt_ops` heartbeat URL (read from AGT on origin/dev, checked 2026-09-28). Image versions are set fleet-wide by dcre-infra `scripts/switch-version.sh` (mandates stages from the 2.3 release line, checked 2026-09-28); the cluster itself is defined in dcre-infra. Releases are digits-only 3-component SemVer tags, uniform across the fleet.

## Related repositories

The complete, current list of live DCRE repositories (stage services, orchestrator, platform libraries, infra and tooling) lives in one place: the [DCRE design register README](https://github.com/sean-huni/dcre-design-register#repositories). Deprecated and archived repositories are deliberately absent from it. This README does not copy that list, so it cannot drift.

- Design register: https://github.com/sean-huni/dcre-design-register (start at `docs/specs/DESIGN-REGISTER.md`; the diagrams in `docs/diagrams/` are the specification)
