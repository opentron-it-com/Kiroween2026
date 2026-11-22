package org.opentron.backend.security.confidential.gateway;

import java.util.List;

public interface PiiDetector {
    String version();

    List<DetectedEntity> detect(CharSequence text);
}
