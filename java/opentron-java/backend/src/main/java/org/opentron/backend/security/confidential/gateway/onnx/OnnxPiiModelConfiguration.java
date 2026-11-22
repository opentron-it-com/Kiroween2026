package org.opentron.backend.security.confidential.gateway.onnx;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentron.backend.security.confidential.gateway.PiiDetector;
import org.opentron.backend.security.confidential.gateway.CompositePiiDetector;
import org.opentron.backend.security.confidential.gateway.PrototypeRuleBasedPiiDetector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
@Profile("onnx-pii")
public class OnnxPiiModelConfiguration {
    @Bean
    public OnnxPiiModelArtifactVerifier.VerifiedModel verifiedOnnxPiiModel(
            @Value("${confidential.onnx.pii.model-path}") Path modelPath,
            @Value("${confidential.onnx.pii.model-sha256}") String expectedSha256) {
        return OnnxPiiModelArtifactVerifier.verify(modelPath, expectedSha256);
    }

    @Bean(destroyMethod = "close")
    public OnnxPiiDetector onnxPiiDetector(
            OnnxPiiModelArtifactVerifier.VerifiedModel model,
            ObjectMapper objectMapper,
            @Value("${confidential.onnx.pii.vocab-path}") Path vocabularyPath,
            @Value("${confidential.onnx.pii.special-tokens-path}") Path specialTokensPath,
            @Value("${confidential.onnx.pii.lower-case:true}") boolean lowercase,
            @Value("${confidential.onnx.pii.input-ids-name}") String inputIdsName,
            @Value("${confidential.onnx.pii.attention-mask-name}") String attentionMaskName,
            @Value("${confidential.onnx.pii.logits-name}") String logitsName,
            @Value("${confidential.onnx.pii.labels}") String labels,
            @Value("${confidential.onnx.pii.maximum-sequence-length:512}")
                    int maximumSequenceLength) {
        return OnnxPiiDetector.open(
                model,
                vocabularyPath,
                specialTokensPath,
                lowercase,
                inputIdsName,
                attentionMaskName,
                logitsName,
                labels,
                maximumSequenceLength,
                objectMapper);
    }

    @Bean
    @Primary
    public PiiDetector compositeOnnxPiiDetector(OnnxPiiDetector onnxPiiDetector) {
        return new CompositePiiDetector(
                new PrototypeRuleBasedPiiDetector(), onnxPiiDetector);
    }
}
