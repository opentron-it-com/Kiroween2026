package org.opentron.backend.security.confidential.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PiiDetectorEvaluationTest {
    private static final int WARMUP_ITERATIONS = 2_000;
    private static final int MEASURED_ITERATIONS = 10_000;

    private final PiiDetector detector = new PrototypeRuleBasedPiiDetector();
    private final List<Fixture> corpus = syntheticCorpus();

    @Test
    void reportsQualityCoverageLatencyAndAllocationForSyntheticCorpus() {
        Evaluation evaluation = evaluate();
        Performance performance = measurePerformance();

        assertEquals(20, evaluation.goldCount());
        assertTrue(evaluation.truePositives() >= 9);
        assertTrue(evaluation.precision() >= 0.8);
        assertTrue(evaluation.recall() >= 0.5);
        assertTrue(evaluation.f1() >= 0.65);
        assertTrue(evaluation.falsePositives() >= 0);
        assertTrue(evaluation.falseNegatives() > 0,
                "The corpus must keep unsupported entity categories visible.");
        assertTrue(performance.p95Nanos() > 0);
        assertTrue(performance.allocatedBytesPerDetection() >= -1);

        System.out.printf(Locale.ROOT,
                "PII evaluation detector=%s corpus=%d gold=%d predicted=%d "
                        + "tp=%d fp=%d fn=%d precision=%.3f recall=%.3f f1=%.3f "
                        + "p50_ns=%d p95_ns=%d allocated_bytes_per_detection=%s%n",
                detector.version(),
                corpus.size(),
                evaluation.goldCount(),
                evaluation.predictedCount(),
                evaluation.truePositives(),
                evaluation.falsePositives(),
                evaluation.falseNegatives(),
                evaluation.precision(),
                evaluation.recall(),
                evaluation.f1(),
                performance.p50Nanos(),
                performance.p95Nanos(),
                performance.allocatedBytesPerDetection() < 0
                        ? "unavailable"
                        : Long.toString(performance.allocatedBytesPerDetection()));
        evaluation.categoryRecall().forEach((category, recall) ->
                System.out.printf(Locale.ROOT, "  category=%s recall=%.3f%n", category, recall));
        evaluation.languageRecall().forEach((language, recall) ->
                System.out.printf(Locale.ROOT, "  language=%s recall=%.3f%n", language, recall));
    }

    private Evaluation evaluate() {
        int truePositives = 0;
        int predictedCount = 0;
        int goldCount = 0;
        Map<String, int[]> categoryCounts = new HashMap<>();
        Map<String, int[]> languageCounts = new HashMap<>();

        for (Fixture fixture : corpus) {
            List<DetectedEntity> predicted = detector.detect(fixture.text());
            predictedCount += predicted.size();
            goldCount += fixture.gold().size();
            boolean[] matchedGold = new boolean[fixture.gold().size()];
            for (DetectedEntity result : predicted) {
                int matchingIndex = exactGoldMatch(fixture.gold(), result);
                if (matchingIndex >= 0 && !matchedGold[matchingIndex]) {
                    matchedGold[matchingIndex] = true;
                    truePositives++;
                    GoldEntity gold = fixture.gold().get(matchingIndex);
                    categoryCounts.computeIfAbsent(gold.category(), ignored -> new int[2])[0]++;
                    languageCounts.computeIfAbsent(fixture.language(), ignored -> new int[2])[0]++;
                }
            }
            for (GoldEntity gold : fixture.gold()) {
                categoryCounts.computeIfAbsent(gold.category(), ignored -> new int[2])[1]++;
                languageCounts.computeIfAbsent(fixture.language(), ignored -> new int[2])[1]++;
            }
        }

        int falsePositives = predictedCount - truePositives;
        int falseNegatives = goldCount - truePositives;
        double precision = ratio(truePositives, predictedCount);
        double recall = ratio(truePositives, goldCount);
        double f1 = precision + recall == 0
                ? 0
                : 2 * precision * recall / (precision + recall);
        return new Evaluation(
                goldCount,
                predictedCount,
                truePositives,
                falsePositives,
                falseNegatives,
                precision,
                recall,
                f1,
                recalls(categoryCounts),
                recalls(languageCounts));
    }

    private Performance measurePerformance() {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            detectCorpusOnce();
        }

        long threadId = Thread.currentThread().threadId();
        com.sun.management.ThreadMXBean allocationBean = allocationBean();
        long allocatedBefore = allocationBean == null
                ? -1
                : allocationBean.getThreadAllocatedBytes(threadId);
        long[] elapsedNanos = new long[MEASURED_ITERATIONS];
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            Fixture fixture = corpus.get(i % corpus.size());
            long start = System.nanoTime();
            detector.detect(fixture.text());
            elapsedNanos[i] = System.nanoTime() - start;
        }
        long allocatedAfter = allocationBean == null
                ? -1
                : allocationBean.getThreadAllocatedBytes(threadId);

        java.util.Arrays.sort(elapsedNanos);
        long allocatedBytesPerDetection =
                allocatedBeanAvailable(allocatedBefore, allocatedAfter)
                        ? Math.max(0, (allocatedAfter - allocatedBefore) / MEASURED_ITERATIONS)
                        : -1;
        return new Performance(
                percentile(elapsedNanos, 0.50),
                percentile(elapsedNanos, 0.95),
                allocatedBytesPerDetection);
    }

    private void detectCorpusOnce() {
        for (Fixture fixture : corpus) {
            detector.detect(fixture.text());
        }
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        var bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean allocationBean
                && allocationBean.isThreadAllocatedMemorySupported()) {
            if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
                allocationBean.setThreadAllocatedMemoryEnabled(true);
            }
            return allocationBean;
        }
        return null;
    }

    private static boolean allocatedBeanAvailable(long before, long after) {
        return before >= 0 && after >= before;
    }

    private static int exactGoldMatch(
            List<GoldEntity> gold, DetectedEntity predicted) {
        for (int i = 0; i < gold.size(); i++) {
            GoldEntity expected = gold.get(i);
            if (expected.start() == predicted.start()
                    && expected.end() == predicted.end()
                    && expected.detectorType().equals(predicted.type())) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, Double> recalls(Map<String, int[]> counts) {
        Map<String, Double> result = new java.util.TreeMap<>();
        counts.forEach((key, value) -> result.put(key, ratio(value[0], value[1])));
        return Map.copyOf(result);
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = Math.min(sorted.length - 1,
                (int) Math.ceil(percentile * sorted.length) - 1);
        return sorted[index];
    }

    private static List<Fixture> syntheticCorpus() {
        return List.of(
                fixture("en-email", "en", "Email ada@example.com for a reply.",
                        entity("EMAIL", "ada@example.com")),
                fixture("en-phone", "en", "Call (212) 555-0199 after lunch.",
                        entity("PHONE_NUMBER", "(212) 555-0199")),
                fixture("en-mixed", "en", "Reach bob.smith+work@example.org or 415-555-0132.",
                        entity("EMAIL", "bob.smith+work@example.org"),
                        entity("PHONE_NUMBER", "415-555-0132")),
                fixture("en-negative", "en", "The example address is user at example dot com."),
                fixture("en-name", "en", "Ask Ada Lovelace to review the report.",
                        entity("PERSON_NAME", "Ada Lovelace")),
                fixture("en-address", "en", "Ship it to 221B Baker Street, London.",
                        entity("POSTAL_ADDRESS", "221B Baker Street, London")),
                fixture("en-payment", "en", "Test card 4111 1111 1111 1111.",
                        entity("PAYMENT_CARD", "4111 1111 1111 1111")),
                fixture("en-ssn", "en", "SSN: 078-05-1120.",
                        entity("GOVERNMENT_ID", "078-05-1120")),
                fixture("en-iban", "en", "IBAN GB82 WEST 1234 5698 7654 32.",
                        entity("IBAN", "GB82 WEST 1234 5698 7654 32")),
                fixture("en-invalid-iban", "en", "Invalid IBAN GB83 WEST 1234 5698 7654 32."),
                fixture("fr-iban", "fr", "IBAN FR1420041010050500013M02606.",
                        entity("IBAN", "FR1420041010050500013M02606")),
                fixture("fr-phone", "fr", "Appelez le +33 6 12 34 56 78 demain.",
                        entity("PHONE_INTERNATIONAL", "+33 6 12 34 56 78")),
                fixture("fr-email-idn", "fr", "Écrivez à alice@bücher.de, merci.",
                        entity("EMAIL_IDN", "alice@bücher.de")),
                fixture("de-phone", "de", "Rufen Sie +49 30 123456 an.",
                        entity("PHONE_INTERNATIONAL", "+49 30 123456")),
                fixture("es-phone", "es", "Llame al +34 612 345 678.",
                        entity("PHONE_INTERNATIONAL", "+34 612 345 678")),
                fixture("es-name", "es", "Consulte a María-José Álvarez.",
                        entity("PERSON_NAME", "María-José Álvarez")),
                fixture("de-address", "de", "Lieferung: Hauptstraße 5, 10115 Berlin.",
                        entity("POSTAL_ADDRESS", "Hauptstraße 5, 10115 Berlin")),
                fixture("zh-phone", "zh", "联系电话 +86 138 0013 8000。",
                        entity("PHONE_INTERNATIONAL", "+86 138 0013 8000")),
                fixture("ja-id", "ja", "個人番号は1234-5678-9012です。",
                        entity("GOVERNMENT_ID", "1234-5678-9012")),
                fixture("en-health", "en", "Diagnosis: type 2 diabetes.",
                        entity("HEALTH_INFORMATION", "type 2 diabetes")),
                fixture("en-ambiguous", "en", "The code 123-456-7890 is a ticket number.",
                        entity("TICKET_NUMBER", "123-456-7890")));
    }

    private static Fixture fixture(String id, String language, String text, GoldEntity... values) {
        List<GoldEntity> gold = new ArrayList<>();
        for (GoldEntity value : values) {
            int start = text.indexOf(value.literal());
            if (start < 0) {
                throw new IllegalStateException("Synthetic label is absent from fixture " + id);
            }
            gold.add(new GoldEntity(
                    value.category(),
                    value.detectorType(),
                    value.literal(),
                    start,
                    start + value.literal().length()));
        }
        gold.sort(Comparator.comparingInt(GoldEntity::start));
        return new Fixture(id, language, text, List.copyOf(gold));
    }

    private static GoldEntity entity(String category, String literal) {
        String detectorType = switch (category) {
            case "EMAIL", "EMAIL_IDN" -> "EMAIL";
            case "PHONE_NUMBER", "PHONE_INTERNATIONAL" -> "PHONE_NUMBER";
            case "PAYMENT_CARD" -> "CREDIT_CARD";
            case "GOVERNMENT_ID" -> "SSN";
            case "IBAN" -> "IBAN";
            default -> "";
        };
        return new GoldEntity(category, detectorType, literal, -1, -1);
    }

    private record Fixture(String id, String language, String text, List<GoldEntity> gold) {
    }

    private record GoldEntity(
            String category,
            String detectorType,
            String literal,
            int start,
            int end) {
    }

    private record Evaluation(
            int goldCount,
            int predictedCount,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            double precision,
            double recall,
            double f1,
            Map<String, Double> categoryRecall,
            Map<String, Double> languageRecall) {
    }

    private record Performance(
            long p50Nanos,
            long p95Nanos,
            long allocatedBytesPerDetection) {
    }
}
