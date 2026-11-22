package org.opentron.backend.security.confidential.local;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/confidential")
@Profile("!aws-nitro & !gcp-cvm & !local-untrusted & !prod & !gateway-oidc")
public class LocalConfidentialEnvelopeController {
    private final LocalConfidentialEnvelopeService envelopeService;

    public LocalConfidentialEnvelopeController(LocalConfidentialEnvelopeService envelopeService) {
        this.envelopeService = envelopeService;
    }

    @GetMapping("/local-envelope-key")
    public ResponseEntity<LocalConfidentialEnvelopeService.PublicKeyResponse> getLocalEnvelopeKey() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(envelopeService.publicKey());
    }
}
