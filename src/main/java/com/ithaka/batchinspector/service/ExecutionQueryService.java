package com.ithaka.batchinspector.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ithaka.batchinspector.config.DashboardProperties;
import com.ithaka.batchinspector.config.DataSourceRegistry;
import com.ithaka.batchinspector.config.DataSourceRegistry.DbAccess;
import com.ithaka.batchinspector.dto.ContextBlock;
import com.ithaka.batchinspector.dto.ExecutionDetail;
import com.ithaka.batchinspector.dto.ExecutionSearchResult;
import com.ithaka.batchinspector.dto.ExecutionSummary;
import com.ithaka.batchinspector.dto.HealthResult;
import com.ithaka.batchinspector.dto.ParameterDto;
import com.ithaka.batchinspector.dto.StepDto;
import com.ithaka.batchinspector.exception.DashboardException;
import com.ithaka.batchinspector.exception.DashboardException.Kind;
import com.ithaka.batchinspector.exception.DbErrorTranslator;
import com.ithaka.batchinspector.model.BatchStatus;
import com.ithaka.batchinspector.model.SearchCriteria;
import com.ithaka.batchinspector.repository.BatchMetadataRepository;
import com.ithaka.batchinspector.repository.BatchMetadataRepository.RawContext;
import com.ithaka.batchinspector.util.Formatters;

/** Orchestrates read-only metadata queries. Every database call runs in a read-only transaction. */
@Service
public class ExecutionQueryService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionQueryService.class);

    private final DataSourceRegistry registry;
    private final BatchMetadataRepository repo;
    private final TimelineBuilder timelineBuilder;
    private final SensitiveMasker masker;
    private final DashboardProperties props;
    private final Formatters fmt;

    public ExecutionQueryService(DataSourceRegistry registry, BatchMetadataRepository repo, TimelineBuilder timelineBuilder,
                                 SensitiveMasker masker, DashboardProperties props, Formatters fmt) {
        this.registry = registry;
        this.repo = repo;
        this.timelineBuilder = timelineBuilder;
        this.masker = masker;
        this.props = props;
        this.fmt = fmt;
    }

    public ExecutionSearchResult search(SearchCriteria c) {
        DbAccess a = registry.access(c.dbId());
        try {
            return a.tx().execute(tx -> {
                Map<BatchStatus, Long> counts = repo.countByStatus(a, c.withStatus(null));
                long total = counts.values().stream().mapToLong(Long::longValue).sum();
                long matching = c.status() == null ? total : counts.getOrDefault(c.status(), 0L);
                List<ExecutionSummary> rows = matching == 0 ? List.of() : repo.search(a, c);
                long maxDur = rows.stream()
                        .map(r -> fmt.elapsedMs(r.startTime(), r.endTime(), r.status()))
                        .filter(d -> d != null).mapToLong(Long::longValue).max().orElse(0L);
                return new ExecutionSearchResult(counts, total, matching, rows, c.limit(), maxDur);
            });
        } catch (RuntimeException e) {
            throw fail(e, a);
        }
    }

    public ExecutionDetail detail(String dbId, long executionId) {
        DbAccess a = registry.access(dbId);
        try {
            return a.tx().execute(tx -> {
                ExecutionSummary base = repo.findExecution(a, executionId)
                        .orElseThrow(() -> new DashboardException(Kind.NOT_FOUND, "Job execution " + executionId + " was not found."));
                List<StepDto> steps = repo.findSteps(a, executionId);
                List<ParameterDto> params = repo.findParameters(a, executionId).stream()
                        .map(p -> masker.isSensitive(p.name())
                                ? new ParameterDto(p.name(), p.type(), SensitiveMasker.MASK, p.identifying(), true) : p)
                        .toList();
                String jobKey = repo.findJobKey(a, base.instanceId());
                int failed = (int) steps.stream().filter(s -> BatchStatus.of(s.status()) == BatchStatus.FAILED).count();
                ExecutionSummary summary = new ExecutionSummary(base.id(), base.instanceId(), base.jobName(), base.status(),
                        base.version(), base.createTime(), base.startTime(), base.endTime(), base.lastUpdated(),
                        base.exitCode(), base.exitMessage(), steps.size(), failed);
                long slowest = steps.stream()
                        .map(s -> fmt.elapsedMs(s.startTime(), s.endTime(), s.status()))
                        .filter(d -> d != null).mapToLong(Long::longValue).max().orElse(0L);
                return new ExecutionDetail(summary, jobKey, params, steps, timelineBuilder.build(summary, steps), slowest);
            });
        } catch (RuntimeException e) {
            throw fail(e, a);
        }
    }

    /** Optional diagnostics: job + step execution contexts, size-limited, masked, never deserialised. */
    public List<ContextBlock> contexts(String dbId, long executionId) {
        DbAccess a = registry.access(dbId);
        int max = props.maxContextChars();
        try {
            return a.tx().execute(tx -> {
                List<ContextBlock> blocks = new ArrayList<>();
                RawContext job = repo.findJobContext(a, executionId, max).orElse(null);
                blocks.add(toBlock("Job execution context", "job", job));
                for (RawContext sc : repo.findStepContexts(a, executionId, max)) {
                    blocks.add(toBlock("Step: " + sc.ownerName() + "  (#" + sc.ownerId() + ")", "step", sc));
                }
                return blocks;
            });
        } catch (RuntimeException e) {
            throw fail(e, a);
        }
    }

    public HealthResult health(String dbId) {
        long t = System.nanoTime();
        DbAccess a = registry.access(dbId);
        try {
            return a.tx().execute(tx -> {
                repo.ping(a);
                List<String> missing = repo.probeTables(a);
                long ms = (System.nanoTime() - t) / 1_000_000;
                if (missing.isEmpty()) {
                    return new HealthResult(true, "Connected - all six metadata tables are readable.", ms, List.of());
                }
                return new HealthResult(false, "Connected, but some metadata tables are not readable (check prefix/schema/grants).", ms, missing);
            });
        } catch (RuntimeException e) {
            throw fail(e, a);
        }
    }

    private ContextBlock toBlock(String title, String scope, RawContext rc) {
        if (rc == null || rc.text() == null || rc.text().isBlank()) {
            return new ContextBlock(title, scope, "", false, false, true);
        }
        SensitiveMasker.Masked m = masker.maskContext(rc.text(), rc.truncated());
        return new ContextBlock(title, scope, m.text(), rc.truncated(), m.masked(), false);
    }

    private DashboardException fail(RuntimeException e, DbAccess a) {
        DashboardException de = DbErrorTranslator.translate(e, a.db().displayName(), a.tables().exec());
        if (de.kind() == Kind.DATABASE) {
            // Log the cause message only (no connection string, no credentials, no SQL text with values).
            log.warn("Query against database '{}' failed: {}", a.db().id(), rootMessage(e));
            log.debug("Detailed failure", e);
        }
        return de;
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getClass().getSimpleName() + ": " + String.valueOf(r.getMessage()).split("\n")[0];
    }
}
