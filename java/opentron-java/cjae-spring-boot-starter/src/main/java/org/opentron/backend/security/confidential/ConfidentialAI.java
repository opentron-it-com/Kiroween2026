package org.opentron.backend.security.confidential;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires an approved confidential execution policy before invoking a
 * Spring-proxied method or any method on a Spring-proxied type carrying this
 * annotation.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfidentialAI {
}
