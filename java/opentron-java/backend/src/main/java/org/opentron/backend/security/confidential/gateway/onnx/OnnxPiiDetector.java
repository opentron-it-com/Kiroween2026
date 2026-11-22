package org.opentron.backend.security.confidential.gateway.onnx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentron.backend.security.confidential.gateway.DetectedEntity;
import org.opentron.backend.security.confidential.gateway.PiiDetector;

public final class OnnxPiiDetector implements PiiDetector, AutoCloseable {
    private final OrtSession session;
    private final BertWordPieceTokenizer tokenizer;
    private final String inputIdsName;
    private final String attentionMaskName;
    private final String tokenTypeIdsName;
    private final String logitsName;
    private final Map<Long, EntityLabel> labels;
    private final int maximumSequenceLength;
    private final int tensorSequenceLength;
    private final int labelCount;
    private final AtomicBoolean closed = new AtomicBoolean();

    OnnxPiiDetector(
            OrtSession session,
            BertWordPieceTokenizer tokenizer,
            String inputIdsName,
            String attentionMaskName,
            String tokenTypeIdsName,
            String logitsName,
            Map<Long, EntityLabel> labels,
            int maximumSequenceLength,
            int tensorSequenceLength,
            int labelCount) {
        this.session = Objects.requireNonNull(session, "session");
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.inputIdsName = requireName(inputIdsName, "input IDs");
        this.attentionMaskName = requireName(attentionMaskName, "attention mask");
        this.tokenTypeIdsName = tokenTypeIdsName;
        this.logitsName = requireName(logitsName, "logits");
        if (this.inputIdsName.equals(this.attentionMaskName)
                || this.inputIdsName.equals(this.logitsName)
                || this.attentionMaskName.equals(this.logitsName)
                || this.inputIdsName.equals(this.tokenTypeIdsName)
                || this.attentionMaskName.equals(this.tokenTypeIdsName)
                || this.logitsName.equals(this.tokenTypeIdsName)) {
            throw new IllegalArgumentException("ONNX input and output tensor names must be distinct.");
        }
        this.labels = Map.copyOf(labels);
        if (maximumSequenceLength < 3
                || (tensorSequenceLength > 0 && tensorSequenceLength < maximumSequenceLength)
                || labelCount < 1
                || this.labels.isEmpty()) {
            throw new IllegalArgumentException("ONNX sequence and label configuration is invalid.");
        }
        this.maximumSequenceLength = maximumSequenceLength;
        this.tensorSequenceLength = tensorSequenceLength;
        this.labelCount = labelCount;
    }

    public static OnnxPiiDetector open(
            OnnxPiiModelArtifactVerifier.VerifiedModel model,
            java.nio.file.Path vocabularyPath,
            java.nio.file.Path specialTokensPath,
            boolean lowercase,
            String inputIdsName,
            String attentionMaskName,
            String logitsName,
            String labelJson,
            int maximumSequenceLength,
            ObjectMapper objectMapper) {
        Objects.requireNonNull(model, "model");
        Map<Long, EntityLabel> labels = parseLabels(labelJson, objectMapper);
        try {
            ai.onnxruntime.OrtEnvironment environment =
                    ai.onnxruntime.OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            OrtSession session;
            try {
                session = environment.createSession(model.path().toString(), options);
            } finally {
                options.close();
            }

            try {
                ModelContract contract = validateModelContract(
                        session,
                        inputIdsName,
                        attentionMaskName,
                        logitsName,
                        maximumSequenceLength,
                        labels.size());
                BertWordPieceTokenizer tokenizer = new BertWordPieceTokenizer(
                        vocabularyPath, specialTokensPath, lowercase, objectMapper);
                return new OnnxPiiDetector(
                        session,
                        tokenizer,
                        inputIdsName,
                        attentionMaskName,
                        contract.tokenTypeIdsName(),
                        logitsName,
                        labels,
                        maximumSequenceLength,
                        contract.tensorSequenceLength(),
                        labels.size());
            } catch (RuntimeException exception) {
                session.close();
                throw exception;
            }
        } catch (OrtException exception) {
            throw new IllegalStateException("Unable to initialize the configured ONNX PII model.", exception);
        }
    }

    @Override
    public String version() {
        return "onnx-token-classification-1";
    }

    @Override
    public List<DetectedEntity> detect(CharSequence text) {
        if (text == null) {
            throw new IllegalArgumentException("Text must not be null.");
        }
        if (closed.get()) {
            throw new IllegalStateException("ONNX PII detector is closed.");
        }
        List<BertWordPieceTokenizer.Token> tokens = tokenizer.tokenize(text);
        if (tokens.isEmpty()) {
            return List.of();
        }

        List<DetectedEntity> detected = new ArrayList<>();
        int contentLimit = maximumSequenceLength - 2;
        int overlap = Math.min(64, contentLimit / 4);
        int step = Math.max(1, contentLimit - overlap);
        for (int windowStart = 0; windowStart < tokens.size(); windowStart += step) {
            int windowEnd = Math.min(tokens.size(), windowStart + contentLimit);
            inferWindow(tokens, windowStart, windowEnd, detected);
        }
        return mergeOverlappingEntities(detected);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                session.close();
            } catch (OrtException exception) {
                throw new IllegalStateException("Unable to close ONNX PII model session.", exception);
            }
        }
    }

    private void inferWindow(
            List<BertWordPieceTokenizer.Token> tokens,
            int start,
            int end,
            List<DetectedEntity> detected) {
        int sequenceLength = end - start + 2;
        int tensorLength = tensorSequenceLength > 0
                ? tensorSequenceLength
                : sequenceLength;
        int[] predictedLabels;
        try (DirectLongInput ids = new DirectLongInput(tensorLength);
                DirectLongInput attentionMask = new DirectLongInput(tensorLength);
                DirectLongInput tokenTypeIds = tokenTypeIdsName == null
                        ? null
                        : new DirectLongInput(tensorLength)) {
            ids.fill(tokenizer.paddingId());
            if (tokenTypeIds != null) {
                tokenTypeIds.fill(0);
            }
            ids.put(0, tokenizer.classificationId());
            for (int index = 0; index < sequenceLength; index++) {
                attentionMask.put(index, 1);
            }
            for (int index = start; index < end; index++) {
                ids.put(index - start + 1, tokens.get(index).id());
            }
            ids.put(sequenceLength - 1, tokenizer.separatorId());
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(inputIdsName, ids.tensor());
            inputs.put(attentionMaskName, attentionMask.tensor());
            if (tokenTypeIds != null) {
                inputs.put(tokenTypeIdsName, tokenTypeIds.tensor());
            }
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxValue value = result.get(logitsName)
                        .orElseThrow(() -> new IllegalStateException(
                                "Configured ONNX session did not return the logits output."));
                if (!(value instanceof OnnxTensor logits)) {
                    throw new IllegalStateException("ONNX logits output is not a tensor.");
                }
                predictedLabels = readPredictedLabels(logits, tensorLength);
            } catch (OrtException exception) {
                throw new IllegalStateException("ONNX PII inference failed.", exception);
            }
        }
        try {
            collectWindowEntities(tokens, start, end, predictedLabels, detected);
        } finally {
            java.util.Arrays.fill(predictedLabels, -1);
        }
    }

    private int[] readPredictedLabels(OnnxTensor logits, int sequenceLength) {
        TensorInfo info = logits.getInfo();
        long[] shape = info.getShape();
        if (info.type != ai.onnxruntime.OnnxJavaType.FLOAT
                || shape.length != 3
                || shape[0] != 1
                || shape[1] != sequenceLength
                || shape[2] != labelCount) {
            throw new IllegalStateException("ONNX logits tensor does not match the configured output contract.");
        }
        java.nio.FloatBuffer values = logits.getFloatBuffer();
        int[] predictions = new int[sequenceLength];
        for (int token = 0; token < sequenceLength; token++) {
            int bestLabel = 0;
            float bestScore = Float.NEGATIVE_INFINITY;
            int rowOffset = token * labelCount;
            for (int label = 0; label < labelCount; label++) {
                float score = values.get(rowOffset + label);
                if (!Float.isFinite(score)) {
                    throw new IllegalStateException("ONNX logits contain a non-finite value.");
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestLabel = label;
                }
            }
            predictions[token] = bestLabel;
        }
        return predictions;
    }

    private void collectWindowEntities(
            List<BertWordPieceTokenizer.Token> tokens,
            int start,
            int end,
            int[] predictions,
            List<DetectedEntity> output) {
        String activeType = null;
        int entityStart = -1;
        int entityEnd = -1;
        for (int index = 0; index < end - start; index++) {
            EntityLabel label = labels.get((long) predictions[index + 1]);
            if (label == null || label.type() == null) {
                if (activeType != null) {
                    output.add(new DetectedEntity(activeType, entityStart, entityEnd));
                    activeType = null;
                }
                continue;
            }

            BertWordPieceTokenizer.Token token = tokens.get(start + index);
            int tokenStart = token.wordStart();
            int tokenEnd = token.wordEnd();
            boolean continuation = label.continuation()
                    && label.type().equals(activeType);
            if (continuation) {
                entityEnd = Math.max(entityEnd, tokenEnd);
                continue;
            }
            if (activeType != null) {
                output.add(new DetectedEntity(activeType, entityStart, entityEnd));
            }
            activeType = label.type();
            entityStart = tokenStart;
            entityEnd = tokenEnd;
        }
        if (activeType != null) {
            output.add(new DetectedEntity(activeType, entityStart, entityEnd));
        }
    }

    private static List<DetectedEntity> mergeOverlappingEntities(List<DetectedEntity> entities) {
        entities.sort(Comparator.comparingInt(DetectedEntity::start)
                .thenComparingInt(DetectedEntity::end));
        List<DetectedEntity> merged = new ArrayList<>();
        for (DetectedEntity entity : entities) {
            if (!merged.isEmpty()) {
                DetectedEntity previous = merged.get(merged.size() - 1);
                if (entity.start() < previous.end()) {
                    merged.set(merged.size() - 1, new DetectedEntity(
                            previous.type(), previous.start(), Math.max(previous.end(), entity.end())));
                    continue;
                }
            }
            merged.add(entity);
        }
        return List.copyOf(merged);
    }

    private static Map<Long, EntityLabel> parseLabels(String json, ObjectMapper objectMapper) {
        Objects.requireNonNull(json, "labelJson");
        Objects.requireNonNull(objectMapper, "objectMapper");
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject() || root.isEmpty()) {
                throw new IllegalArgumentException("ONNX label mapping must be a non-empty JSON object.");
            }
            Map<Long, EntityLabel> labels = new TreeMap<>();
            for (var entry = root.fields(); entry.hasNext();) {
                Map.Entry<String, JsonNode> field = entry.next();
                long id;
                try {
                    id = Long.parseLong(field.getKey());
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("ONNX label IDs must be non-negative integers.");
                }
                if (id < 0 || !field.getValue().isTextual()) {
                    throw new IllegalArgumentException("ONNX label mapping has an invalid entry.");
                }
                String label = field.getValue().textValue();
                EntityLabel parsed;
                if ("O".equals(label)) {
                    parsed = new EntityLabel(null, false);
                } else if (label.matches("[BI]-[A-Z][A-Z0-9_]{0,63}")) {
                    parsed = new EntityLabel(label.substring(2), label.charAt(0) == 'I');
                } else {
                    throw new IllegalArgumentException("ONNX labels must use O or BIO entity labels.");
                }
                if (labels.putIfAbsent(id, parsed) != null) {
                    throw new IllegalArgumentException("ONNX label mapping contains duplicate IDs.");
                }
            }
            long expected = 0;
            for (long id : labels.keySet()) {
                if (id != expected++) {
                    throw new IllegalArgumentException("ONNX label IDs must be contiguous from zero.");
                }
            }
            return Map.copyOf(labels);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("ONNX label mapping is not valid JSON.", exception);
        }
    }

    private static ModelContract validateModelContract(
            OrtSession session,
            String idsName,
            String maskName,
            String logitsName,
            int maximumSequenceLength,
            int labelCount) {
        try {
            Map<String, NodeInfo> inputs = session.getInputInfo();
            String tokenTypeIdsName = inputs.containsKey("token_type_ids")
                    ? "token_type_ids"
                    : null;
            if (inputs.size() != (tokenTypeIdsName == null ? 2 : 3)
                    || !inputs.containsKey(idsName)
                    || !inputs.containsKey(maskName)
                    || (tokenTypeIdsName != null
                            && (tokenTypeIdsName.equals(idsName) || tokenTypeIdsName.equals(maskName)))) {
                throw new IllegalStateException(
                        "ONNX model inputs must be input_ids and attention_mask, with optional token_type_ids.");
            }
            TensorInfo ids = requireTensorInfo(inputs, idsName);
            TensorInfo mask = requireTensorInfo(inputs, maskName);
            TensorInfo tokenTypeIds = tokenTypeIdsName == null
                    ? null
                    : requireTensorInfo(inputs, tokenTypeIdsName);
            if (ids.type != ai.onnxruntime.OnnxJavaType.INT64
                    || mask.type != ai.onnxruntime.OnnxJavaType.INT64
                    || ids.getShape().length != 2
                    || mask.getShape().length != 2
                    || !supportsSingleBatch(ids.getShape()[0])
                    || !supportsSingleBatch(mask.getShape()[0])
                    || (tokenTypeIds != null
                            && (tokenTypeIds.type != ai.onnxruntime.OnnxJavaType.INT64
                                    || tokenTypeIds.getShape().length != 2
                                    || !supportsSingleBatch(tokenTypeIds.getShape()[0])))) {
                throw new IllegalStateException(
                        "ONNX model must accept rank-two INT64 input_ids, attention_mask, and optional token_type_ids tensors.");
            }
            TensorInfo logits = requireTensorInfo(session.getOutputInfo(), logitsName);
            long[] outputShape = logits.getShape();
            if (logits.type != ai.onnxruntime.OnnxJavaType.FLOAT
                    || outputShape.length != 3
                    || !supportsSingleBatch(outputShape[0])
                    || (outputShape[2] > 0 && outputShape[2] != labelCount)) {
                throw new IllegalStateException(
                        "ONNX model output must be FLOAT32 [batch, sequence, configured labels].");
            }
            validateSequenceDimension(ids.getShape()[1], maximumSequenceLength);
            validateSequenceDimension(mask.getShape()[1], maximumSequenceLength);
            if (tokenTypeIds != null) {
                validateSequenceDimension(tokenTypeIds.getShape()[1], maximumSequenceLength);
            }
            validateSequenceDimension(outputShape[1], maximumSequenceLength);
            long idsLength = ids.getShape()[1];
            long maskLength = mask.getShape()[1];
            long tokenTypeIdsLength = tokenTypeIds == null
                    ? 0
                    : tokenTypeIds.getShape()[1];
            long outputLength = outputShape[1];
            if ((idsLength > 0 && maskLength > 0 && idsLength != maskLength)
                    || (idsLength > 0 && tokenTypeIdsLength > 0 && idsLength != tokenTypeIdsLength)
                    || (maskLength > 0 && tokenTypeIdsLength > 0 && maskLength != tokenTypeIdsLength)
                    || (idsLength > 0 && outputLength > 0 && idsLength != outputLength)
                    || (maskLength > 0 && outputLength > 0 && maskLength != outputLength)
                    || (tokenTypeIdsLength > 0
                            && outputLength > 0
                            && tokenTypeIdsLength != outputLength)) {
                throw new IllegalStateException(
                        "ONNX input and output sequence dimensions do not match.");
            }
            long fixedSequenceLength = idsLength > 0
                    ? idsLength
                    : maskLength > 0
                            ? maskLength
                            : tokenTypeIdsLength > 0 ? tokenTypeIdsLength : outputLength;
            return new ModelContract(
                    fixedSequenceLength > 0 ? Math.toIntExact(fixedSequenceLength) : 0,
                    tokenTypeIdsName);
        } catch (OrtException exception) {
            throw new IllegalStateException("Unable to inspect ONNX model tensor metadata.", exception);
        }
    }

    private static TensorInfo requireTensorInfo(Map<String, NodeInfo> nodes, String name) {
        NodeInfo node = nodes.get(name);
        if (node == null || !(node.getInfo() instanceof TensorInfo tensorInfo)) {
            throw new IllegalStateException("ONNX model is missing configured tensor: " + name);
        }
        return tensorInfo;
    }

    private static void validateSequenceDimension(long dimension, int maximumSequenceLength) {
        if (dimension > 0 && dimension < maximumSequenceLength) {
            throw new IllegalStateException(
                    "Configured ONNX maximum sequence length exceeds the model tensor dimension.");
        }
    }

    private static boolean supportsSingleBatch(long dimension) {
        return dimension <= 0 || dimension == 1;
    }

    private static String requireName(String name, String label) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ONNX " + label + " tensor name must not be blank.");
        }
        return name;
    }

    record EntityLabel(String type, boolean continuation) {
    }

    private record ModelContract(int tensorSequenceLength, String tokenTypeIdsName) {
    }

    private static final class DirectLongInput implements AutoCloseable {
        private final ByteBuffer bytes;
        private final LongBuffer values;
        private final OnnxTensor tensor;

        private DirectLongInput(int length) {
            bytes = ByteBuffer.allocateDirect(Math.multiplyExact(length, Long.BYTES))
                    .order(ByteOrder.nativeOrder());
            values = bytes.asLongBuffer();
            try {
                tensor = OnnxTensor.createTensor(
                        ai.onnxruntime.OrtEnvironment.getEnvironment(),
                        values,
                        new long[] {1, length});
            } catch (OrtException exception) {
                for (int index = 0; index < values.capacity(); index++) {
                    values.put(index, 0L);
                }
                throw new IllegalStateException("Unable to allocate ONNX input tensor.", exception);
            }
        }

        private void put(int index, long value) {
            values.put(index, value);
        }

        private void fill(long value) {
            for (int index = 0; index < values.capacity(); index++) {
                values.put(index, value);
            }
        }

        private OnnxTensor tensor() {
            return tensor;
        }

        @Override
        public void close() {
            try {
                tensor.close();
            } finally {
                for (int index = 0; index < values.capacity(); index++) {
                    values.put(index, 0L);
                }
                bytes.clear();
                while (bytes.hasRemaining()) {
                    bytes.put((byte) 0);
                }
            }
        }
    }
}
