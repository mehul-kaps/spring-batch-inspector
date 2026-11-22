package com.ithaka.batchinspector.service;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ithaka.batchinspector.config.DashboardProperties;

/** Masks values whose key/name looks sensitive. Operates on text/JSON trees only - nothing is ever deserialised into objects. */
@Component
public class SensitiveMasker {

    public static final String MASK = "••••••";

    private final List<String> patterns;
    private final Pattern textFallback;
    private final ObjectMapper mapper;

    public SensitiveMasker(DashboardProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        this.patterns = props.sensitiveParameterPatterns().stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT)).toList();
        String alt = patterns.isEmpty() ? "(?!)" : String.join("|", patterns.stream().map(Pattern::quote).toList());
        this.textFallback = Pattern.compile("(\"[^\"]*(?:" + alt + ")[^\"]*\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"", Pattern.CASE_INSENSITIVE);
    }

    public boolean isSensitive(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return patterns.stream().anyMatch(n::contains);
    }

    public record Masked(String text, boolean masked) {
    }

    /** Pretty-prints JSON context text (when it parses) and masks sensitive keys. Falls back to regex masking for truncated text. */
    public Masked maskContext(String raw, boolean truncated) {
        if (raw == null) return new Masked("", false);
        if (!truncated) {
            try {
                JsonNode tree = mapper.readTree(raw);
                boolean[] hit = {false};
                maskTree(tree, hit);
                return new Masked(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree), hit[0]);
            } catch (Exception ignored) {
                // not JSON (or not parseable) -> regex fallback below
            }
        }
        var m = textFallback.matcher(raw);
        boolean found = m.find();
        return new Masked(found ? m.replaceAll("$1\"" + MASK + "\"") : raw, found);
    }

    private void maskTree(JsonNode node, boolean[] hit) {
        if (node instanceof ObjectNode obj) {
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (isSensitive(e.getKey())) {
                    e.setValue(TextNode.valueOf(MASK));
                    hit[0] = true;
                } else {
                    maskTree(e.getValue(), hit);
                }
            }
        } else if (node != null && node.isArray()) {
            node.forEach(n -> maskTree(n, hit));
        }
    }
}
