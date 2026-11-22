package com.ithaka.batchinspector.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ithaka.batchinspector.config.DashboardProperties;
import com.ithaka.batchinspector.config.DataSourceRegistry;
import com.ithaka.batchinspector.dto.DatabaseOption;
import com.ithaka.batchinspector.dto.ExecutionDetail;
import com.ithaka.batchinspector.dto.ExecutionSearchResult;
import com.ithaka.batchinspector.dto.HealthResult;
import com.ithaka.batchinspector.exception.DashboardException;
import com.ithaka.batchinspector.model.BatchStatus;
import com.ithaka.batchinspector.model.FilterForm;
import com.ithaka.batchinspector.model.TimeRange;
import com.ithaka.batchinspector.service.ExecutionQueryService;
import com.ithaka.batchinspector.service.SearchCriteriaFactory;

/** Every endpoint is a GET and only reads. There is intentionally no POST/PUT/DELETE mapping anywhere in this application. */
@Controller
public class DashboardController {

    /** One status tile in the summary strip. */
    public record StatusCard(String code, String label, String css, String glyph, long count, boolean active) {
    }

    private final DashboardProperties props;
    private final DataSourceRegistry registry;
    private final ExecutionQueryService service;
    private final SearchCriteriaFactory criteriaFactory;
    private final ObjectMapper mapper;

    public DashboardController(DashboardProperties props, DataSourceRegistry registry, ExecutionQueryService service,
                               SearchCriteriaFactory criteriaFactory, ObjectMapper mapper) {
        this.props = props;
        this.registry = registry;
        this.service = service;
        this.criteriaFactory = criteriaFactory;
        this.mapper = mapper;
    }

    @GetMapping("/")
    public String index(@RequestParam(defaultValue = "") String db,
                        @RequestParam(defaultValue = "") String range,
                        @RequestParam(defaultValue = "") String from,
                        @RequestParam(defaultValue = "") String to,
                        @RequestParam(defaultValue = "") String executionId,
                        @RequestParam(defaultValue = "") String instanceId,
                        @RequestParam(defaultValue = "") String jobName,
                        @RequestParam(defaultValue = "") String max,
                        @RequestParam(defaultValue = "all") String status,
                        @RequestParam(defaultValue = "") String selected,
                        @RequestParam(defaultValue = "false") boolean search,
                        Model model) {

        List<DatabaseOption> options = registry.options();
        String effectiveDb = props.find(db).or(props::defaultDb).map(DashboardProperties.Database::id).orElse("");
        String effectiveRange = range.isBlank() ? TimeRange.DEFAULT.code() : TimeRange.fromCode(range).code();
        String effectiveMax = max.isBlank() ? String.valueOf(props.defaultMaxResults()) : max.trim();
        FilterForm form = new FilterForm(effectiveDb, effectiveRange, from, to, executionId.trim(), instanceId.trim(),
                jobName.trim(), effectiveMax, status.trim().isEmpty() ? "all" : status.trim(), selected.trim());

        model.addAttribute("databases", options);
        model.addAttribute("noDatabases", options.isEmpty());
        model.addAttribute("current", options.stream().filter(o -> o.id().equals(effectiveDb)).findFirst().orElse(null));
        model.addAttribute("f", form);
        model.addAttribute("ranges", TimeRange.ALL_VALUES);
        model.addAttribute("defaultMax", props.defaultMaxResults());
        model.addAttribute("maxLimit", props.maxResultsLimit());
        model.addAttribute("searched", false);
        model.addAttribute("notes", List.of());

        if (search && !effectiveDb.isEmpty()) {
            model.addAttribute("searched", true);
            try {
                SearchCriteriaFactory.Parsed parsed = criteriaFactory.parse(form);
                ExecutionSearchResult result = service.search(parsed.criteria());
                model.addAttribute("result", result);
                model.addAttribute("notes", parsed.notes());
                model.addAttribute("cards", cards(result, parsed.criteria().status()));
            } catch (DashboardException e) {
                model.addAttribute("error", e.getMessage());
            }
        }
        return "index";
    }

    private List<StatusCard> cards(ExecutionSearchResult r, BatchStatus active) {
        List<StatusCard> list = new ArrayList<>();
        list.add(new StatusCard("all", "All", "all", "◎", r.total(), active == null));
        for (BatchStatus s : BatchStatus.CARD_ORDER) {
            long n = r.counts().getOrDefault(s, 0L);
            if (s == BatchStatus.UNKNOWN && n == 0 && active != BatchStatus.UNKNOWN) continue; // only when applicable
            list.add(new StatusCard(s.name(), s.label(), s.css(), s.glyph(), n, active == s));
        }
        return list;
    }

    // ---------------------------------------------------------------- detail panes (HTML fragments)

    @GetMapping("/executions/{id}")
    public String detail(@PathVariable long id, @RequestParam String db, Model model) {
        try {
            ExecutionDetail d = service.detail(db, id);
            model.addAttribute("d", d);
            model.addAttribute("db", db);
            model.addAttribute("maxChars", (long) props.maxContextChars());
            model.addAttribute("timelineJson", safeJson(d.timeline()));
            return "fragments/detail :: detail";
        } catch (DashboardException e) {
            model.addAttribute("error", e.getMessage());
            return "fragments/detail :: error";
        }
    }

    @GetMapping("/executions/{id}/context")
    public String context(@PathVariable long id, @RequestParam String db, Model model) {
        try {
            model.addAttribute("blocks", service.contexts(db, id));
            model.addAttribute("maxChars", props.maxContextChars());
            return "fragments/detail :: context";
        } catch (DashboardException e) {
            model.addAttribute("error", e.getMessage());
            return "fragments/detail :: error";
        }
    }

    // ---------------------------------------------------------------- connection test (on demand only)

    @GetMapping("/databases/{id}/health")
    @ResponseBody
    public HealthResult health(@PathVariable String id) {
        try {
            return service.health(id);
        } catch (DashboardException e) {
            return new HealthResult(false, e.getMessage(), 0, List.of());
        }
    }

    private String safeJson(Object o) {
        try {
            // "</" is escaped so the JSON can never terminate a surrounding <script> element.
            return mapper.writeValueAsString(o).replace("</", "<\\/");
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
