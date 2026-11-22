package org.opentron.cjae.worker;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.PriorityQueue;

import org.opentron.backend.security.confidential.ConfidentialSegment;

final class AwsNitroRagPipeline {
    static final int EMBEDDING_DIMENSIONS = 16;
    static final int MAX_RECORDS = 2_000_000;
    private static final int MAX_TOP_K = 100;
    private static final Pattern SYNTHETIC_QUERY =
            Pattern.compile("segment-(\\d{1,2}) region-(\\d{1,2}) product-(\\d{1,3})");
    private static final ValueLayout.OfInt INT_LAYOUT = ValueLayout.JAVA_INT_UNALIGNED;
    private static final Comparator<Match> BEST_FIRST =
            Comparator.comparingDouble(Match::similarity)
                    .reversed()
                    .thenComparingInt(Match::recordId);

    Result run(int recordCount, String query, int topK) {
        validate(recordCount, query, topK);
        long started = System.nanoTime();
        long indexBytes = Math.multiplyExact(
                Math.multiplyExact((long) recordCount, EMBEDDING_DIMENSIONS),
                Integer.BYTES);
        int[] queryVector = embed(query.split(" "), 0);

        try (ConfidentialSegment index = ConfidentialSegment.allocate(indexBytes)) {
            MemorySegment memory = index.memory();
            buildIndex(memory, recordCount);
            List<Match> matches = retrieve(memory, recordCount, queryVector, topK);
            String prompt = packagePrompt(query, matches);
            return new Result(
                    recordCount,
                    indexBytes,
                    System.nanoTime() - started,
                    matches,
                    sha256(prompt),
                    prompt);
        }
    }

    static String syntheticRecord(int id) {
        return "customer-" + id
                + " segment-" + id % 31
                + " region-" + (id * 17L + 5) % 23
                + " product-" + (id * 7L + 3) % 101
                + " tier-" + (id * 13L + 1) % 7;
    }

    static boolean isSyntheticQuery(String query) {
        if (query == null) {
            return false;
        }
        Matcher matcher = SYNTHETIC_QUERY.matcher(query);
        if (!matcher.matches()) {
            return false;
        }
        return Integer.parseInt(matcher.group(1)) < 31
                && Integer.parseInt(matcher.group(2)) < 23
                && Integer.parseInt(matcher.group(3)) < 101;
    }

    static int[] embed(String[] tokens) {
        return embed(tokens, 1);
    }

    private static int[] embed(String[] tokens, int firstToken) {
        int[] vector = new int[EMBEDDING_DIMENSIONS];
        for (int index = firstToken; index < tokens.length; index++) {
            int hash = fnv1a(tokens[index]);
            int bucket = hash & (EMBEDDING_DIMENSIONS - 1);
            vector[bucket] += hash < 0 ? -1 : 1;
        }
        return vector;
    }

    private static void validate(int recordCount, String query, int topK) {
        if (recordCount < 1 || recordCount > MAX_RECORDS) {
            throw new IllegalArgumentException(
                    "RAG benchmark record count must be between 1 and " + MAX_RECORDS + ".");
        }
        if (!isSyntheticQuery(query)) {
            throw new IllegalArgumentException("RAG benchmark query must be synthetic text.");
        }
        if (topK < 1 || topK > MAX_TOP_K || topK > recordCount) {
            throw new IllegalArgumentException("RAG benchmark top-k value is invalid.");
        }
    }

    private static void buildIndex(MemorySegment memory, int recordCount) {
        for (int id = 0; id < recordCount; id++) {
            int[] vector = embed(syntheticRecord(id).split(" "));
            long offset = (long) id * EMBEDDING_DIMENSIONS;
            for (int dimension = 0; dimension < EMBEDDING_DIMENSIONS; dimension++) {
                memory.setAtIndex(INT_LAYOUT, offset + dimension, vector[dimension]);
            }
        }
    }

    private static List<Match> retrieve(
            MemorySegment memory, int recordCount, int[] queryVector, int topK) {
        int queryNorm = squaredNorm(queryVector);
        PriorityQueue<Match> bestMatches = new PriorityQueue<>(topK, BEST_FIRST.reversed());
        for (int id = 0; id < recordCount; id++) {
            long offset = (long) id * EMBEDDING_DIMENSIONS;
            int dotProduct = 0;
            int recordNorm = 0;
            for (int dimension = 0; dimension < EMBEDDING_DIMENSIONS; dimension++) {
                int value = memory.getAtIndex(INT_LAYOUT, offset + dimension);
                dotProduct += value * queryVector[dimension];
                recordNorm += value * value;
            }
            if (recordNorm == 0 || queryNorm == 0) {
                continue;
            }
            Match candidate = new Match(
                    id,
                    dotProduct,
                    recordNorm,
                    dotProduct / Math.sqrt((double) recordNorm * queryNorm));
            if (bestMatches.size() < topK) {
                bestMatches.add(candidate);
            } else if (BEST_FIRST.compare(candidate, bestMatches.peek()) < 0) {
                bestMatches.remove();
                bestMatches.add(candidate);
            }
        }
        return bestMatches.stream().sorted(BEST_FIRST).toList();
    }

    private static int squaredNorm(int[] vector) {
        int result = 0;
        for (int value : vector) {
            result += value * value;
        }
        return result;
    }

    private static int fnv1a(String value) {
        int hash = 0x811c9dc5;
        for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ Byte.toUnsignedInt(item)) * 0x01000193;
        }
        return hash;
    }

    private static String packagePrompt(String query, List<Match> matches) {
        StringBuilder prompt = new StringBuilder("query=").append(query).append('\n');
        for (Match match : matches) {
            prompt.append(match.recordId())
                    .append(',')
                    .append(match.dotProduct())
                    .append(',')
                    .append(match.recordNormSquared())
                    .append('\n');
        }
        return prompt.toString();
    }

    private static String sha256(String prompt) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(prompt.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    record Match(int recordId, int dotProduct, int recordNormSquared, double similarity) {
    }

    record Result(
            int recordCount,
            long nativeIndexBytes,
            long elapsedNanos,
            List<Match> topMatches,
            String promptSha256,
            String syntheticPrompt) {

        Result {
            topMatches = List.copyOf(topMatches);
        }

        List<Integer> topRecordIds() {
            return topMatches.stream().map(Match::recordId).toList();
        }
    }
}
