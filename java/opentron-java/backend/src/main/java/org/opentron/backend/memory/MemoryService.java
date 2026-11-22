package org.opentron.backend.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MemoryService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryService.class);
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]{3,}");
    private static final Set<String> QUERY_STOP_WORDS = Set.of(
            "about", "after", "again", "and", "are", "before", "between", "did", "does",
            "from", "have", "into", "just", "more", "most", "that", "the", "their",
            "them", "there", "these", "they", "this", "those", "through", "under",
            "what", "when", "where", "which", "who", "with", "would", "your"
    );

    private final ConcurrentHashMap<String, MemoryEntry> store = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path filePath;

    public MemoryService() {
        this("memory.jsonl");
    }

    public MemoryService(String filePath) {
        this.filePath = Path.of(filePath);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try {
            if (Files.exists(this.filePath)) {
                Files.lines(this.filePath).forEach(line -> {
                    try {
                        MemoryEntry e = mapper.readValue(line, MemoryEntry.class);
                        store.put(e.getId(), e);
                        } catch (IOException ex) {
                        logger.warn("Failed to parse memory line", ex);
                    }
                });
            }
        } catch (IOException e) {
            logger.warn("Failed to read memory file", e);
        }
    }

    public MemoryEntry store(MemoryStoreRequest req) {
        String id = UUID.randomUUID().toString();
        MemoryEntry e = new MemoryEntry(id, req.getText(), req.getMetadata());
        store.put(id, e);
        persist(e);
        return e;
    }

    public List<MemoryEntry> search(MemorySearchRequest req) {
        return search(req, entry -> true);
    }

    public List<MemoryEntry> searchArchive(MemorySearchRequest req) {
        return search(req, entry ->
                entry.getMetadata() != null
                        && "file_upload".equals(entry.getMetadata().get("source")));
    }

    private List<MemoryEntry> search(MemorySearchRequest req, Predicate<MemoryEntry> filter) {
        String q = req.getQuery() == null ? "" : req.getQuery().toLowerCase();
        int limit = Math.max(0, req.getLimit());
        if (limit == 0) return List.of();

        List<MemoryEntry> exactMatches = store.values().stream()
                .filter(filter)
                .filter(e -> e.getText() != null && e.getText().toLowerCase().contains(q))
                .limit(limit)
                .collect(Collectors.toList());
        if (!exactMatches.isEmpty() || q.isBlank()) return exactMatches;

        Set<String> queryWords = words(q).stream()
                .filter(word -> !QUERY_STOP_WORDS.contains(word))
                .collect(Collectors.toSet());
        if (queryWords.isEmpty()) return List.of();

        return store.values().stream()
                .filter(filter)
                .map(entry -> new SearchHit(entry, relevance(entry, queryWords)))
                .filter(hit -> hit.score() > 0)
                .sorted((left, right) -> Integer.compare(right.score(), left.score()))
                .limit(limit)
                .map(SearchHit::entry)
                .collect(Collectors.toList());
    }

    private static int relevance(MemoryEntry entry, Set<String> queryWords) {
        return (int) words(entry.getText()).stream().filter(queryWords::contains).count();
    }

    private static Set<String> words(String text) {
        if (text == null) return Set.of();
        Matcher matcher = WORD_PATTERN.matcher(text.toLowerCase());
        return matcher.results().map(match -> match.group()).collect(Collectors.toSet());
    }

    private record SearchHit(MemoryEntry entry, int score) {}

    public int count() {
        return store.size();
    }

    public Map<String, Object> config() {
        return Map.of("engine", "memory-inproc", "version", "0.1");
    }

    public Map<String, Object> index(MemoryStoreRequest req) {
        // no-op: return simple status
        return Map.of("status", "ok");
    }

    private synchronized void persist(MemoryEntry e) {
        try {
            String json = mapper.writeValueAsString(e);
            Files.writeString(this.filePath, json + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            logger.warn("Failed to persist memory entry", ex);
        }
    }
}
