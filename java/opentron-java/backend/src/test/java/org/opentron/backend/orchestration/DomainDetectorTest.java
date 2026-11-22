package org.opentron.backend.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the bug where a multi-domain request only ever
 * got a single ("Backend") specialist response: the previous analyzeRequest()
 * implementation used an if/else-if chain that could return at most one
 * domain, and "backend" was checked first so it always won.
 */
class DomainDetectorTest {

    @Test
    void multiPartLabelledRequestReturnsAllFourDomains() {
        String request = "Need: (1) Backend — Spring Boot + Postgres schema with row-level security for "
                + "tenant isolation, REST endpoints for workspace CRUD, JWT-based auth; "
                + "(2) Frontend — React/TS workspace switcher component with accessible dropdown and "
                + "optimistic UI updates; (3) QA — test plan covering tenant-isolation edge cases and RLS "
                + "bypass attempts; (4) DevOps — Docker Compose and basic CI pipeline to deploy it.";

        List<String> domains = DomainDetector.detectDomains(request, "");

        assertEquals(List.of("backend", "frontend", "qa", "devops"), domains);
        assertTrue(DomainDetector.isMultiDomain(request, ""));
    }

    @Test
    void singleDomainKeywordRequestStaysSingle() {
        String request = "Optimize this Spring Boot database query, it's causing high CPU under load.";

        List<String> domains = DomainDetector.detectDomains(request, "");

        assertEquals(List.of("backend"), domains);
        assertFalse(DomainDetector.isMultiDomain(request, ""));
    }

    @Test
    void unstructuredMultiDomainKeywordsAreAllDetected_notJustTheFirst() {
        // No explicit labels here - relies purely on the independent keyword
        // checks. This is the case the old else-if chain broke: "database"
        // (backend) appears before "UI" (frontend) in the sentence, so the
        // old code would return only ["backend"].
        String request = "The database schema needs a redesign and the UI dropdown for switching "
                + "workspaces needs to update optimistically.";

        List<String> domains = DomainDetector.detectDomains(request, "");

        assertEquals(List.of("backend", "frontend"), domains);
        assertTrue(DomainDetector.isMultiDomain(request, ""));
    }

    @Test
    void genericRequestWithNoDomainKeywordsFallsBackToKnowledge() {
        String request = "What's on my calendar tomorrow?";

        List<String> domains = DomainDetector.detectDomains(request, "");

        assertEquals(List.of("knowledge"), domains);
        assertFalse(DomainDetector.isMultiDomain(request, ""));
    }

    @Test
    void shortAmbiguousTokensDoNotFalsePositiveOnSubstrings() {
        // "build" contains "ui" and "capital"/"rapid" contain "api" as bare
        // substrings - word-boundary matching must not treat those as UI/API
        // mentions.
        String request = "We need to build a rapid financial model for capital allocation.";

        List<String> domains = DomainDetector.detectDomains(request, "");

        assertEquals(List.of("knowledge"), domains);
    }

    @Test
    void labelledDomainsWinOverIncidentalKeywordsElsewhereInText() {
        String request = "(1) Frontend — build the dashboard. Mention of a Java backend service exists "
                + "elsewhere in our stack but is out of scope here.";

        List<String> domains = DomainDetector.detectDomains(request, "");

        // Both "frontend" (labelled) and "backend"/"java" (bare keyword,
        // unlabelled) appear, but once ANY explicit label is found, labelled
        // detection takes over entirely rather than mixing with keyword
        // hits, so only the labelled domain is returned.
        assertEquals(List.of("frontend"), domains);
    }
}
