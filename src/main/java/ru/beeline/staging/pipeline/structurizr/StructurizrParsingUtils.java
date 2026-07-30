package ru.beeline.staging.pipeline.structurizr;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing rules shared between StructurizrSequenceValidator and the Structurizr workspace decomposer,
 * per structurizr-sequence-transform-rules.md: locating the target softwareSystem by workspace_cmdb,
 * parsing the RPS/LATENCY/ERROR_RATE/TC SLA string on an operation property, and turning a relationship
 * description or an operation property key into the same canonical key so the two can be compared.
 */
public final class StructurizrParsingUtils {

    // No leading ^ anchor: real dynamicView relationship descriptions commonly prefix the method
    // with free-text (e.g. "Получения информации по IP адресу\n GET /city/{ip}"), so the method can
    // appear anywhere in the text, not just at position 0.
    private static final Pattern HTTP_METHOD_PATTERN =
            Pattern.compile("\\b(GET|POST|PUT|DELETE|PATCH|OPTIONS|HEAD)\\b", Pattern.CASE_INSENSITIVE);

    /** Interface (type=api) component property keys that are metadata, not operation names. */
    public static final Set<String> RESERVED_INTERFACE_PROPERTY_KEYS = Set.of(
            "external_name", "api_url", "protocol", "version", "tc", "code", "parents", "source");

    private StructurizrParsingUtils() {}

    /**
     * Canonical keys (see {@link #canonicalOperationKey}) of every operation declared on a type=api
     * component's properties, across all containers of the given softwareSystem — the set of methods
     * that "exist for an interface described in some container of the product".
     */
    public static Set<String> collectOperationCanonicalKeys(JsonNode targetSystem) {
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode container : targetSystem.path("containers")) {
            for (JsonNode component : container.path("components")) {
                if (!"api".equals(textOrNull(component, "type"))) continue;
                Iterator<Map.Entry<String, JsonNode>> fields = component.path("properties").fields();
                while (fields.hasNext()) {
                    String propertyKey = fields.next().getKey();
                    if (RESERVED_INTERFACE_PROPERTY_KEYS.contains(propertyKey.toLowerCase())) continue;
                    String canonicalKey = canonicalOperationKey(propertyKey);
                    if (canonicalKey != null) keys.add(canonicalKey);
                }
            }
        }
        return keys;
    }

    public static String workspaceCmdb(JsonNode root) {
        return textOrNull(root.path("model").path("properties"), "workspace_cmdb");
    }

    /** softwareSystem whose properties.cmdb equals the given cmdb. */
    public static JsonNode targetSoftwareSystem(JsonNode root, String cmdb) {
        IndexedNode found = targetSoftwareSystemEntry(root, cmdb);
        return found != null ? found.node() : null;
    }

    public record IndexedNode(JsonNode node, int index) {}

    /**
     * Same lookup as {@link #targetSoftwareSystem}, but also returns its index for building precise
     * JSON pointers. Matched by properties.cmdb — NOT properties."structurizr.dsl.identifier", which
     * is just the DSL variable name the architect happened to pick (e.g. "my_system", or a
     * hierarchical GUID for deployment-node workspaces) and only coincidentally equals cmdb sometimes.
     */
    public static IndexedNode targetSoftwareSystemEntry(JsonNode root, String cmdb) {
        if (cmdb == null) return null;
        int idx = 0;
        for (JsonNode system : root.path("model").path("softwareSystems")) {
            JsonNode properties = system.path("properties");
            String matchKey = firstNonBlank(textOrNull(properties, "cmdb"), textOrNull(properties, "structurizr.dsl.identifier"));
            if (cmdb.equals(matchKey)) return new IndexedNode(system, idx);
            idx++;
        }
        return null;
    }

    /** RPS:100;LATENCY:50;ERROR_RATE:0.1;TC=... — supports both ':' and '=' pair delimiters, keys upper-cased. */
    public static Map<String, String> parseSlaProperties(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value == null || value.isBlank()) return result;
        for (String pair : value.split(";")) {
            if (pair.isBlank()) continue;
            int sep = indexOfDelimiter(pair);
            if (sep < 0) continue;
            String key = pair.substring(0, sep).trim().toUpperCase();
            String val = pair.substring(sep + 1).trim();
            if (!key.isEmpty()) result.put(key, val);
        }
        return result;
    }

    private static int indexOfDelimiter(String pair) {
        int eq = pair.indexOf('=');
        int colon = pair.indexOf(':');
        if (eq < 0) return colon;
        if (colon < 0) return eq;
        return Math.min(eq, colon);
    }

    public static String httpMethodOf(String text) {
        if (text == null) return null;
        Matcher m = HTTP_METHOD_PATTERN.matcher(text.trim());
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    /**
     * Canonical comparison key for an operation name: REST -> "{METHOD} {lowercased path without
     * trailing slash}", SOAP -> lower-cased bare method name. Used both to register an operation
     * (from an interface component's property key) and to resolve a dynamicView relationship's
     * description to that same operation.
     */
    public static String canonicalOperationKey(String rawText) {
        if (rawText == null) return null;
        String trimmed = rawText.trim();
        if (trimmed.isEmpty()) return null;
        Matcher m = HTTP_METHOD_PATTERN.matcher(trimmed);
        if (m.find()) {
            String method = m.group(1).toUpperCase();
            String path = trimmed.substring(m.end()).trim().replaceAll("/+$", "").toLowerCase();
            return method + " " + path;
        }
        String firstWord = trimmed.split("\\s+")[0];
        return firstWord.toLowerCase();
    }

    /** {interface_external_name}_{operation_name_normalized}: spaces to '_', strip special chars, lower-case. */
    public static String normalizeForUid(String text) {
        if (text == null) return "";
        String normalized = text.trim().toLowerCase()
                .replaceAll("\\s+", "_")
                .replaceAll("[^a-z0-9_]", "");
        return normalized.replaceAll("_+", "_");
    }

    public static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return null;
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
