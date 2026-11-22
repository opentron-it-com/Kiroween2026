package org.opentron.backend.security.confidential;

import java.util.List;
import java.util.Map;

import org.opentron.backend.security.confidential.local.LocalConfidentialEnvelopeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/confidential")
public class ConfidentialExecutionModeController {
    private final List<ConfidentialExecutionPolicy> policies;
    private final ObjectProvider<LocalConfidentialEnvelopeService> localEnvelopeService;

    public ConfidentialExecutionModeController(
            List<ConfidentialExecutionPolicy> policies,
            ObjectProvider<LocalConfidentialEnvelopeService> localEnvelopeService) {
        this.policies = List.copyOf(policies);
        this.localEnvelopeService = localEnvelopeService;
    }

    @GetMapping("/execution-mode")
    public ResponseEntity<Map<String, String>> getExecutionMode() {
        String mode;
        if (policies.size() != 1) {
            mode = "unavailable";
        } else if (localEnvelopeService.getIfAvailable() != null) {
            mode = "local-envelope";
        } else {
            mode = "runtime-verified";
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("mode", mode));
    }
}
