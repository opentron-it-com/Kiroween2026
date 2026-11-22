package org.opentron.cjae.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication(scanBasePackages = {
        "org.opentron.backend.security.confidential.gateway",
        "org.opentron.cjae.worker"
}, exclude = DataSourceAutoConfiguration.class)
@RestController
public class ConfidentialWorkerApplication {
    private final ObjectProvider<AwsNitroStartupStatus> startupStatus;

    public ConfidentialWorkerApplication(ObjectProvider<AwsNitroStartupStatus> startupStatus) {
        this.startupStatus = startupStatus;
    }

    public static void main(String[] args) {
        SpringApplication.run(ConfidentialWorkerApplication.class, args);
    }

    @GetMapping("/health")
    public ResponseEntity<WorkerHealth> health() {
        AwsNitroStartupStatus.Snapshot verification = startupStatus.getObject().snapshot();
        WorkerHealth health = healthPayload(verification);
        boolean ready = health.awsNitroStartup().ready();
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(health);
    }

    static WorkerHealth healthPayload(AwsNitroStartupStatus.Snapshot verification) {
        boolean ready = verification.ready();
        String healthStatus = ready
                ? "ok"
                : "failed".equals(verification.status()) ? "failed" : "starting";
        return new WorkerHealth(
                healthStatus,
                "cjae-confidential-worker",
                verification);
    }

    public record WorkerHealth(
            String status,
            String service,
            AwsNitroStartupStatus.Snapshot awsNitroStartup) {
    }
}
