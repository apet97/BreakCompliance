package me.apet97.breakcompliance.clockify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed adapter boundary for one Clockify detailed-report row.
 *
 * <p>The raw map is retained for audit/debug storage, while the fields the
 * ingestion pipeline depends on are parsed once at the Clockify boundary.
 */
public record DetailedReportEntry(
        String sourceEntryId,
        String userId,
        String userName,
        String projectId,
        String taskId,
        String clientId,
        String description,
        Instant startAt,
        Instant endAt,
        Long durationSeconds,
        Boolean billable,
        List<String> tags,
        Map<String, Object> raw) {

    public static DetailedReportEntry from(JsonNode entry, ObjectMapper mapper) {
        Map<String, Object> raw = mapper.convertValue(entry, new TypeReference<>() {});
        JsonNode interval = entry.path("timeInterval");
        return new DetailedReportEntry(
                firstText(entry, "_id", "id"),
                textOrNull(entry, "userId"),
                textOrNull(entry, "userName"),
                textOrNull(entry, "projectId"),
                textOrNull(entry, "taskId"),
                textOrNull(entry, "clientId"),
                textOrNull(entry, "description"),
                parseInstant(textOrNull(interval, "start")),
                parseInstant(textOrNull(interval, "end")),
                firstLong(entry, interval, "duration"),
                boolOrNull(entry, "billable"),
                List.copyOf(parseTags(entry.path("tags"))),
                raw == null ? Map.of() : raw);
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = textOrNull(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isTextual()) {
            String text = value.asText().trim();
            return text.isEmpty() ? null : text;
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return null;
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Long firstLong(JsonNode first, JsonNode second, String field) {
        Long value = longOrNull(first, field);
        return value == null ? longOrNull(second, field) : value;
    }

    private static Boolean boolOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isTextual()) {
            String raw = value.asText().trim();
            if ("true".equalsIgnoreCase(raw)) {
                return true;
            }
            if ("false".equalsIgnoreCase(raw)) {
                return false;
            }
        }
        return null;
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private static List<String> parseTags(JsonNode tagsNode) {
        if (!tagsNode.isArray()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>(tagsNode.size());
        for (JsonNode tag : tagsNode) {
            String value = tag.isObject() ? textOrNull(tag, "name") : tag.asText(null);
            if (value != null && !value.isBlank()) {
                tags.add(value.trim());
            }
        }
        return tags;
    }
}
