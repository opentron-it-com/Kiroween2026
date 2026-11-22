package org.opentron.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.opentron.backend.memory.MemorySearchRequest;
import org.opentron.backend.memory.MemoryService;
import org.opentron.backend.memory.MemoryStoreRequest;
import org.opentron.backend.storage.entities.IngestJob;
import org.opentron.backend.storage.repositories.IngestJobRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;

@Service
public class IngestService {

    private static final Logger logger = LoggerFactory.getLogger(IngestService.class);
    private final IngestJobRepository ingestJobRepository;
    private final MemoryService memoryService;
    private final Map<String, IngestJob> jobCache = new ConcurrentHashMap<>();
    
    private static final int CHUNK_SIZE = 8192;
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024;
    private static final int MAX_CHUNKS_PER_ITEM = 100;
    

    public IngestService(IngestJobRepository ingestJobRepository, MemoryService memoryService) {
        this.ingestJobRepository = ingestJobRepository;
        this.memoryService = memoryService;
    }

    public String createJob(String jobId) {
        return createJob(jobId, "unknown");
    }

    public String createJob(String jobId, String connectorType) {
        IngestJob job = new IngestJob(jobId, connectorType);
        job.setStatus("queued");
        ingestJobRepository.save(job);
        jobCache.put(jobId, job);
        logger.info("[IngestService] Created job: {} (connector: {})", jobId, connectorType);
        return jobId;
    }

    public String getJobState(String jobId) {
        IngestJob job = jobCache.getOrDefault(jobId,
            ingestJobRepository.findByJobId(jobId).orElse(null));
        return job != null ? job.getStatus() : "unknown";
    }

    public Map<String, Object> getJobDetails(String jobId) {
        IngestJob job = jobCache.getOrDefault(jobId,
            ingestJobRepository.findByJobId(jobId).orElse(null));
        if (job == null) {
            return Map.of("status", "unknown", "message", "Job not found");
        }
        Map<String, Object> details = new HashMap<>();
        details.put("job_id", job.getJobId());
        details.put("status", job.getStatus());
        details.put("connector", job.getConnectorType());
        details.put("message", job.getMessage());
        details.put("items_processed", job.getItemsProcessed());
        details.put("items_failed", job.getItemsFailed());
        details.put("bytes_ingested", job.getBytesIngested());
        details.put("chunks_created", job.getChunksCreated());
        details.put("source", job.getSource());
        details.put("created_at", job.getCreatedAt());
        details.put("started_at", job.getStartedAt());
        details.put("completed_at", job.getCompletedAt());
        details.put("duration_ms", job.getDurationMs());
        return details;
    }

    public List<Map<String, Object>> searchArchive(String query, int limit) {
        MemorySearchRequest request = new MemorySearchRequest();
        request.setQuery(query);
        request.setLimit(Math.max(0, Math.min(limit, 10)));

        return memoryService.searchArchive(request).stream()
            .map(entry -> {
                Map<String, Object> metadata = entry.getMetadata();
                String fileName = Objects.toString(metadata.get("file_name"), "Imported archive");
                String text = Objects.toString(entry.getText(), "");
                Map<String, Object> result = new HashMap<>();
                result.put("file_name", fileName);
                result.put("excerpt", text.substring(0, Math.min(text.length(), 600)));
                Object chunkIndex = metadata.get("chunk_index");
                if (chunkIndex instanceof Number) {
                    result.put("chunk_index", chunkIndex);
                }
                return result;
            })
            .toList();
    }

    /**
     * Real connector-driven ingest.
     * The fetchFn callable is provided by ConnectorStateService and returns
     * the number of documents fetched so we can update job stats.
     */
    @Async
    public void processIngestWithConnector(String jobId, String connectorId,
                                           Callable<Integer> fetchFn) {
        IngestJob job = jobCache.getOrDefault(jobId,
            ingestJobRepository.findByJobId(jobId).orElse(null));
        if (job == null) {
            logger.warn("[IngestService] Job not found: {}", jobId);
            return;
        }
        try {
            job.setStatus("running");
            job.setStartedAt(Instant.now());
            ingestJobRepository.save(job);

            int itemsProcessed = fetchFn.call();

            job.setItemsProcessed(itemsProcessed);
            job.setChunksCreated(itemsProcessed); // 1 memory entry per doc
            job.setStatus("completed");
            job.setMessage("Fetched and indexed " + itemsProcessed + " documents from " + connectorId);
            job.setCompletedAt(Instant.now());
            ingestJobRepository.save(job);
            jobCache.put(jobId, job);

            logger.info("[IngestService] Connector ingest completed job={} docs={}", jobId, itemsProcessed);
        } catch (Exception e) {
            job.setStatus("failed");
            job.setMessage("Error: " + e.getMessage());
            job.setCompletedAt(Instant.now());
            ingestJobRepository.save(job);
            jobCache.put(jobId, job);
            logger.error("[IngestService] Connector ingest failed job={}", jobId, e);
        }
    }

    @Async
    public void processIngest(String jobId, Map<String, Object> request) {
        IngestJob job = jobCache.getOrDefault(jobId,
            ingestJobRepository.findByJobId(jobId).orElse(null));
        if (job == null) { logger.warn("[IngestService] Job not found: {}", jobId); return; }

        try {
            job.setStatus("running");
            job.setStartedAt(Instant.now());
            ingestJobRepository.save(job);

            String connector = Objects.toString(request.getOrDefault("connector", job.getConnectorType()), "unknown");
            String source    = Objects.toString(request.getOrDefault("source", "unknown"), "unknown");
            Object content   = request.get("content");
            job.setConnectorType(connector);
            job.setSource(source);

            int itemsProcessed = 0;
            long bytesIngested = 0;
            int chunksCreated  = 0;

            if (content instanceof String s && !s.isBlank()) {
                bytesIngested  = s.getBytes(StandardCharsets.UTF_8).length;
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source", source);
                metadata.put("job_id", jobId);
                if (request.get("metadata") instanceof Map<?, ?> requestMetadata) {
                    requestMetadata.forEach((key, value) -> {
                        if (key instanceof String metadataKey && value != null) {
                            metadata.put(metadataKey, value);
                        }
                    });
                }
                chunksCreated = storeChunks(s, metadata);
                itemsProcessed = chunksCreated > 0 ? 1 : 0;
            } else if (request.containsKey("items_synced")) {
                Object n = request.get("items_synced");
                itemsProcessed = n instanceof Number num ? num.intValue() : 0;
                bytesIngested  = itemsProcessed * 1024L;
                chunksCreated  = (itemsProcessed + 9) / 10;
            }

            Thread.sleep(Math.min(5000, Math.max(500, itemsProcessed * 10)));

            job.setItemsProcessed(itemsProcessed);
            job.setBytesIngested(bytesIngested);
            job.setChunksCreated(chunksCreated);
            job.setStatus("completed");
            job.setMessage(String.format("Processed %d items into %d chunks (%.2f KB)",
                itemsProcessed, chunksCreated, bytesIngested / 1024.0));
            job.setCompletedAt(Instant.now());
            ingestJobRepository.save(job);
            jobCache.put(jobId, job);
        } catch (InterruptedException e) {
            job.setStatus("failed"); job.setMessage("Interrupted"); job.setCompletedAt(Instant.now());
            ingestJobRepository.save(job); Thread.currentThread().interrupt();
        } catch (Exception e) {
            job.setStatus("failed"); job.setMessage("Error: " + e.getMessage()); job.setCompletedAt(Instant.now());
            ingestJobRepository.save(job);
            logger.error("[IngestService] Job failed: {}", jobId, e);
        }
    }

    @Async
    public void processFileIngest(String jobId, Path directory) {
        IngestJob job = jobCache.getOrDefault(jobId,
            ingestJobRepository.findByJobId(jobId).orElse(null));
        if (job == null) { logger.warn("[IngestService] Job not found: {}", jobId); return; }

        try {
            job.setStatus("running"); job.setStartedAt(Instant.now());
            job.setConnectorType("file_upload"); ingestJobRepository.save(job);

            int itemsProcessed = 0, itemsFailed = 0;
            long totalBytes = 0; int totalChunks = 0;

            try (var stream = Files.walk(directory)) {
                for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                    try {
                        if (Files.size(file) > MAX_FILE_SIZE) { itemsFailed++; continue; }
                        String content = readFileContent(file);
                        if (content != null && !content.isBlank()) {
                            Map<String, Object> metadata = Map.of(
                                "source", "file_upload",
                                "file_name", file.getFileName().toString(),
                                "job_id", jobId
                            );
                            int pc = storeChunks(content, metadata);
                            if (pc == 0) { itemsFailed++; continue; }
                            totalChunks += pc;
                            totalBytes  += content.getBytes(StandardCharsets.UTF_8).length;
                            itemsProcessed++;
                        } else {
                            itemsFailed++;
                        }
                    } catch (Exception e) { itemsFailed++; }
                }
            }

            job.setItemsProcessed(itemsProcessed); job.setItemsFailed(itemsFailed);
            job.setBytesIngested(totalBytes); job.setChunksCreated(totalChunks);
            job.setStatus(itemsProcessed == 0 && itemsFailed > 0 ? "failed" : "completed");
            job.setMessage(String.format("Processed %d files (%d failed) into %d chunks (%.2f MB)",
                itemsProcessed, itemsFailed, totalChunks, totalBytes / (1024.0 * 1024)));
            job.setCompletedAt(Instant.now());
            ingestJobRepository.save(job); jobCache.put(jobId, job);
            cleanupDirectory(directory);
        } catch (Exception e) {
            job.setStatus("failed"); job.setMessage("Error: " + e.getMessage());
            job.setCompletedAt(Instant.now()); ingestJobRepository.save(job);
            logger.error("[IngestService] File ingest failed: {}", jobId, e);
        }
    }

    public boolean cancelJob(String jobId) {
        IngestJob job = jobCache.getOrDefault(jobId,
            ingestJobRepository.findByJobId(jobId).orElse(null));
        if (job == null) return false;
        if ("running".equals(job.getStatus())) {
            job.setStatus("cancelled"); job.setMessage("Cancelled"); job.setCompletedAt(Instant.now());
            ingestJobRepository.save(job); jobCache.put(jobId, job); return true;
        }
        return false;
    }

    public List<Map<String, Object>> getJobs(String status, String connector, int limit) {
        List<IngestJob> jobs;
        if (status != null && connector != null) jobs = ingestJobRepository.findTop10ByConnectorTypeAndStatusOrderByCreatedAtDesc(connector, status);
        else if (status != null) jobs = ingestJobRepository.findByStatus(status);
        else if (connector != null) jobs = ingestJobRepository.findByConnectorType(connector);
        else jobs = ingestJobRepository.findAll();
        return jobs.stream().limit(limit).map(this::jobToMap).toList();
    }

    public List<Map<String, Object>> getActiveJobs() {
        return ingestJobRepository.findActiveJobs().stream().map(this::jobToMap).toList();
    }

    private Map<String, Object> jobToMap(IngestJob job) {
        Map<String, Object> map = new HashMap<>();
        map.put("job_id", job.getJobId()); map.put("status", job.getStatus());
        map.put("connector", job.getConnectorType()); map.put("message", job.getMessage());
        map.put("items_processed", job.getItemsProcessed()); map.put("items_failed", job.getItemsFailed());
        map.put("bytes_ingested", job.getBytesIngested()); map.put("chunks_created", job.getChunksCreated());
        map.put("duration_ms", job.getDurationMs());
        return map;
    }

    private List<String> chunkContent(String content, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) return chunks;
        for (int i = 0; i < content.length(); i += chunkSize)
            chunks.add(content.substring(i, Math.min(i + chunkSize, content.length())));
        return chunks;
    }

    private int storeChunks(String content, Map<String, Object> metadata) {
        List<String> chunks = chunkContent(content, CHUNK_SIZE);
        int chunksToStore = Math.min(chunks.size(), MAX_CHUNKS_PER_ITEM);
        for (int index = 0; index < chunksToStore; index++) {
            MemoryStoreRequest request = new MemoryStoreRequest();
            request.setText(chunks.get(index));
            Map<String, Object> chunkMetadata = new HashMap<>(metadata);
            chunkMetadata.put("chunk_index", index);
            request.setMetadata(chunkMetadata);
            memoryService.store(request);
        }
        return chunksToStore;
    }

    private String readFileContent(Path file) throws IOException {
        try {
            return new Tika().parseToString(file.toFile());
        } catch (TikaException e) {
            throw new IOException("Unable to extract text from uploaded file", e);
        }
    }

    private void cleanupDirectory(Path directory) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException { Files.delete(f); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException { if (e == null) { Files.delete(d); return FileVisitResult.CONTINUE; } throw e; }
            });
        } catch (IOException e) { logger.warn("[IngestService] Cleanup failed: {}", directory, e); }
    }
}
