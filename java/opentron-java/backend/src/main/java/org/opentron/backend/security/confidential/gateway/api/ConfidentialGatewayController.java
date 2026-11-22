package org.opentron.backend.security.confidential.gateway.api;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.opentron.backend.security.confidential.ConfidentialSegment;
import org.opentron.backend.security.confidential.gateway.ConfidentialGatewayService;
import org.opentron.backend.security.confidential.gateway.ConfidentialOperationGovernor;
import org.opentron.backend.security.confidential.gateway.NativeUtf8CharSequence;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/confidential")
@Profile("(local-untrusted) | ((gcp-cvm | aws-nitro) & gateway-oidc)")
@ConditionalOnProperty(name = "confidential.gateway.enabled", havingValue = "true")
public class ConfidentialGatewayController {
    private final ConfidentialGatewayService gatewayService;
    private final GatewayQuotaService quotaService;

    public ConfidentialGatewayController(
            ConfidentialGatewayService gatewayService,
            GatewayQuotaService quotaService) {
        this.gatewayService = gatewayService;
        this.quotaService = quotaService;
    }

    @PostMapping(path = "/sanitize", consumes = "application/json")
    public void sanitize(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) throws IOException {
        GatewayPrincipal principal = GatewayRequestContext.requirePrincipal(httpRequest);
        try (ConfidentialOperationGovernor.Permit permit =
                        gatewayService.acquireOperationPermit();
                ConfidentialSegment prompt = NativePromptJsonReader.readText(
                        httpRequest.getInputStream(), httpRequest.getContentLengthLong())) {
            NativeUtf8CharSequence text = new NativeUtf8CharSequence(prompt);
            quotaService.checkAndConsume(principal, text.length());
            httpResponse.setStatus(HttpServletResponse.SC_OK);
            httpResponse.setContentType("application/json");
            httpResponse.setCharacterEncoding("UTF-8");
            httpResponse.setHeader("Cache-Control", "no-store");
            setTransportSecurityHeader(httpRequest, httpResponse);
            gatewayService.writeSanitizeJson(
                    principal.secureContextBinding(),
                    prompt,
                    permit,
                    httpResponse.getOutputStream());
        }
    }

    @PostMapping("/desanitize")
    public void desanitize(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) throws IOException {
        GatewayPrincipal principal = GatewayRequestContext.requirePrincipal(httpRequest);
        try (ConfidentialOperationGovernor.Permit permit =
                        gatewayService.acquireOperationPermit();
                NativePromptJsonReader.NativeDesanitizeRequest request =
                        NativePromptJsonReader.readDesanitize(
                                httpRequest.getInputStream(),
                                httpRequest.getContentLengthLong())) {
            int responseLength = new NativeUtf8CharSequence(request.response()).length();
            quotaService.checkAndConsume(
                    principal, Math.addExact(responseLength, request.secureContext().length()));
            httpResponse.setStatus(HttpServletResponse.SC_OK);
            httpResponse.setContentType("application/json");
            httpResponse.setCharacterEncoding("UTF-8");
            httpResponse.setHeader("Cache-Control", "no-store");
            setTransportSecurityHeader(httpRequest, httpResponse);
            gatewayService.writeDesanitizeJson(
                    principal.secureContextBinding(),
                    request.response(),
                    request.secureContext(),
                    permit,
                    httpResponse.getOutputStream());
        }
    }

    @PostMapping(path = "/sanitize/batch", consumes = "application/json")
    public void sanitizeBatch(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) throws IOException {
        GatewayPrincipal principal = GatewayRequestContext.requirePrincipal(httpRequest);
        try (ConfidentialOperationGovernor.Permit permit =
                        gatewayService.acquireOperationPermit();
                NativePromptJsonReader.NativePromptBatch prompts =
                        NativePromptJsonReader.readSegments(
                                httpRequest.getInputStream(),
                                httpRequest.getContentLengthLong())) {
            long totalCharacters = 0;
            for (ConfidentialSegment segment : prompts.segments()) {
                totalCharacters += new NativeUtf8CharSequence(segment).length();
            }
            quotaService.checkAndConsume(principal, Math.toIntExact(totalCharacters));
            httpResponse.setStatus(HttpServletResponse.SC_OK);
            httpResponse.setContentType("application/json");
            httpResponse.setCharacterEncoding("UTF-8");
            httpResponse.setHeader("Cache-Control", "no-store");
            setTransportSecurityHeader(httpRequest, httpResponse);
            gatewayService.writeSanitizeBatchJson(
                    principal.secureContextBinding(),
                    prompts.segments(),
                    permit,
                    httpResponse.getOutputStream());
        }
    }

    @PostMapping("/desanitize/batch")
    public void desanitizeBatch(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) throws IOException {
        GatewayPrincipal principal = GatewayRequestContext.requirePrincipal(httpRequest);
        try (ConfidentialOperationGovernor.Permit permit =
                        gatewayService.acquireOperationPermit();
                NativePromptJsonReader.NativeBatchDesanitizeRequest request =
                        NativePromptJsonReader.readBatchDesanitize(
                                httpRequest.getInputStream(),
                                httpRequest.getContentLengthLong())) {
            long responseCharacters = 0;
            for (ConfidentialSegment response : request.responses().segments()) {
                responseCharacters += new NativeUtf8CharSequence(response).length();
            }
            int quotaUnits = Math.toIntExact(
                    responseCharacters + request.secureContext().length());
            quotaService.checkAndConsume(principal, quotaUnits);
            httpResponse.setStatus(HttpServletResponse.SC_OK);
            httpResponse.setContentType("application/json");
            httpResponse.setCharacterEncoding("UTF-8");
            httpResponse.setHeader("Cache-Control", "no-store");
            setTransportSecurityHeader(httpRequest, httpResponse);
            gatewayService.writeDesanitizeBatchJson(
                    principal.secureContextBinding(),
                    request.responses().segments(),
                    request.secureContext(),
                    permit,
                    httpResponse.getOutputStream());
        }
    }

    public record DesanitizeResponse(String desanitizedText) {
    }

    public record BatchDesanitizeResponse(List<String> desanitizedResponses) {
    }

    private static void setTransportSecurityHeader(
            HttpServletRequest request, HttpServletResponse response) {
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000");
        }
    }
}
