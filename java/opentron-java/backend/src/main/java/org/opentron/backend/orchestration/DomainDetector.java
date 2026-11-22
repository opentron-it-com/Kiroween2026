package org.opentron.backend.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DomainDetector - determines which specialist domain(s) a request spans.
 *
 * Used by both:
 *  - MultiAgentCoordinator.CoordinatorAgent.analyzeRequest(), to decide which
 *    specialist agents to dispatch in parallel, and
 *  - the lightweight GET /v1/orchestrate/detect-domains endpoint, which lets
 *    the frontend preview the domain split *before* committing to a full
 *    (paid, multi-call) coordinator run - e.g. to suggest enabling Tron mode
 *    for a request that looks multi-domain.
 *
 * IMPORTANT: every domain is evaluated independently (no early return /
 * else-if chain). A single request can legitimately span backend + frontend
 * + qa + devops at once (e.g. "design the backend API, build the frontend
 * for it, write a QA test plan, and add CI/CD"), and the whole point of the
 * coordinator's parallel dispatch is to run every matching specialist
 * concurrently - not just the first one that happens to match first.
 *
 * A prior version of this logic (formerly inlined in
 * MultiAgentCoordinator.CoordinatorAgent.analyzeRequest()) used an
 * if/else-if chain, so it could only ever return a single domain - and
 * because "backend" was checked first, any request that mentioned backend
 * terms (java, spring, database, api, ...) anywhere would short-circuit to
 * ["backend"] even when it explicitly also asked for frontend/QA/DevOps
 * work. That bug is why multi-domain requests only ever got a Backend
 * response instead of fanning out to all relevant specialists.
 *
 * STRONG vs. WEAK keyword split (this revision): plain keyword-set matching
 * turned out to have the opposite failure mode from the if/else-if bug -
 * a single, indivisible coding task (e.g. "build a small in-memory query
 * engine library in TypeScript with a full test suite") can incidentally
 * trip three unrelated domain patterns at once (`typescript` -> frontend,
 * `test`/`edge cases` -> qa, `sql` -> backend) even though it is one
 * artifact for one specialist to build, not three separable deliverables.
 * Fanning that out to three specialists running in parallel with no shared
 * context produces three independent, mutually incompatible implementations
 * of the same classes/types - a result that cannot be merged without
 * discarding two of the three outputs (see CoordinatorAgent's contract-first
 * phase, which additionally guards the case where a *genuine* multi-domain
 * request still shares one codebase).
 *
 * Each domain's keyword list is now split into STRONG signals (terms that
 * are essentially unambiguous evidence someone wants that specialist's
 * actual work - e.g. "backend", "spring boot", "react", "test plan",
 * "kubernetes") and WEAK signals (terms that show up incidentally in lots of
 * software-engineering prose regardless of domain - e.g. "sql", "typescript",
 * "test"/"edge cases", "monitoring"). Multiple domains only get dispatched in
 * parallel when at least two of them have a STRONG hit; a request where only
 * WEAK signals fire collapses to the single highest-priority matching domain
 * instead of fanning out. Explicit labels (LABEL_PATTERN) and the
 * conceptual-intent bypass still take priority over all of this, unchanged.
 */
public final class DomainDetector {

    private DomainDetector() {}

    private static final List<String> ORDERED_DOMAINS = List.of("backend", "frontend", "qa", "devops");

    // Matches an explicit domain label anywhere in the text, e.g.
    // "(1) Backend — ...", "Frontend:", "- QA -", "DevOps agent:". This is a
    // much stronger signal than bare keyword sniffing when someone
    // deliberately asks for a multi-role breakdown, so if any labels are
    // found they take priority over the keyword patterns below. Note this
    // deliberately does NOT require the label to start a line - real-world
    // multi-part requests are often one flowing, semicolon-separated
    // paragraph rather than separate lines.
    private static final Pattern LABEL_PATTERN = Pattern.compile(
            "(?i)\\b(backend|frontend|qa|devops)\\b\\s*(?:agent)?\\s*[:—–-]");

    // Full keyword sets (STRONG ∪ WEAK) - used to decide whether a domain
    // matches AT ALL, same as before this revision.
    private static final Pattern BACKEND_PATTERN = Pattern.compile(
            "\\b(backend|java|spring|spring boot|database|postgres|postgresql|sql|api|apis|cache|server|http|thread|threads|concurrency|concurrent|row-level security|rls|jwt)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FRONTEND_PATTERN = Pattern.compile(
            "\\b(frontend|react|typescript|component|components|ui|css|dropdown|accessibility|a11y|optimistic ui)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QA_PATTERN = Pattern.compile(
            "\\b(qa|test|tests|testing|debug|debugging|test case|test cases|test plan|edge case|edge cases)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DEVOPS_PATTERN = Pattern.compile(
            "\\b(devops|kubernetes|docker|docker compose|monitor|monitoring|metrics|ci/cd|ci pipeline|deploy|deployment)\\b",
            Pattern.CASE_INSENSITIVE);

    // STRONG-only subsets: terms that are essentially unambiguous evidence of
    // that specialist's actual work, excluded exactly the generic/ambiguous
    // terms that show up in unrelated single-domain prose (sql, typescript,
    // test/tests/testing/edge case(s), thread(s), concurrency/concurrent,
    // cache, http, monitor/monitoring/metrics). These weak terms still count
    // toward the full pattern match above (a domain can still be the sole
    // collapsed match via a weak hit) - they just can't co-establish a
    // *multi*-domain fan-out on their own.
    private static final Pattern BACKEND_STRONG_PATTERN = Pattern.compile(
            "\\b(backend|java|spring|spring boot|database|postgres|postgresql|api|apis|server|row-level security|rls|jwt)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FRONTEND_STRONG_PATTERN = Pattern.compile(
            "\\b(frontend|react|component|components|ui|css|dropdown|accessibility|a11y|optimistic ui)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QA_STRONG_PATTERN = Pattern.compile(
            "\\b(qa|debug|debugging|test case|test cases|test plan)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DEVOPS_STRONG_PATTERN = Pattern.compile(
            "\\b(devops|kubernetes|docker|docker compose|ci/cd|ci pipeline|deploy|deployment)\\b",
            Pattern.CASE_INSENSITIVE);

    // A conceptual/explanatory question ("summarize the differences between X
    // and Y", "what is Postgres RLS?", "compare Docker and Podman") should go
    // to the Knowledge specialist even when it names backend/frontend/etc.
    // technologies - e.g. "PostgreSQL" alone trips BACKEND_PATTERN, which
    // used to route pure explanation requests to the Backend agent as if they
    // were implementation work. This pattern only recognizes the *intent*
    // (summarize/explain/compare/what is/...); it says nothing about domain,
    // so it must be combined with ACTIONABLE_INTENT_PATTERN below before it's
    // safe to override the keyword match.
    private static final Pattern CONCEPTUAL_INTENT_PATTERN = Pattern.compile(
            "(?i)\\b(summarize|summarise|explain|describe|what\\s+is|what\\s+are|compare|comparison|difference|differences|pros\\s+and\\s+cons|trade-?offs?|overview\\s+of|how\\s+does)\\b");

    // If the request also asks for real specialist work - implementation,
    // debugging, review of the user's own code, test plans, deployment, etc.
    // - it is NOT a pure conceptual question, so CONCEPTUAL_INTENT_PATTERN
    // must not override the keyword-based domain match in that case (e.g.
    // "explain and then implement JWT auth in Spring Boot" must still hit
    // backend, not knowledge).
    private static final Pattern ACTIONABLE_INTENT_PATTERN = Pattern.compile(
            "\\b(implement|build|create|write|refactor|debug|fix|optimi[sz]e|set\\s*up|configure|deploy|design\\s+a|design\\s+the|review\\s+my|my\\s+(code|app|project|service|api|database|schema|query|component)|test\\s+plan|edge\\s+cases?)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Returns every domain the request appears to span, in a stable order
     * (backend, frontend, qa, devops), falling back to ["knowledge"] when
     * nothing matches. Never empty, never null.
     */
    public static List<String> detectDomains(String request, String context) {
        String combined = (request == null ? "" : request) + "\n" + (context == null ? "" : context);

        LinkedHashSet<String> labelled = detectLabelledDomains(combined);
        if (!labelled.isEmpty()) {
            return orderDomains(labelled);
        }

        // Pure conceptual/explanatory questions go straight to Knowledge,
        // bypassing keyword sniffing entirely - a technology name mentioned
        // in an explanation request (e.g. "PostgreSQL" in "summarize the
        // differences between JSON and JSONB") is not the same signal as
        // that name appearing in an actual implementation/debugging request.
        if (CONCEPTUAL_INTENT_PATTERN.matcher(combined).find()
                && !ACTIONABLE_INTENT_PATTERN.matcher(combined).find()) {
            return List.of("knowledge");
        }

        LinkedHashSet<String> matched = new LinkedHashSet<>();
        if (BACKEND_PATTERN.matcher(combined).find())  matched.add("backend");
        if (FRONTEND_PATTERN.matcher(combined).find()) matched.add("frontend");
        if (QA_PATTERN.matcher(combined).find())       matched.add("qa");
        if (DEVOPS_PATTERN.matcher(combined).find())   matched.add("devops");

        if (matched.isEmpty()) {
            return List.of("knowledge");
        }

        LinkedHashSet<String> strong = new LinkedHashSet<>();
        if (BACKEND_STRONG_PATTERN.matcher(combined).find())  strong.add("backend");
        if (FRONTEND_STRONG_PATTERN.matcher(combined).find()) strong.add("frontend");
        if (QA_STRONG_PATTERN.matcher(combined).find())       strong.add("qa");
        if (DEVOPS_STRONG_PATTERN.matcher(combined).find())   strong.add("devops");

        if (strong.size() >= 2) {
            // Genuine multi-domain request: at least two specialists have
            // unambiguous evidence they're each individually being asked
            // for. Fan out to every domain that matched at all (strong or
            // weak) - a weak-only domain riding alongside two+ strong ones
            // is plausibly still relevant (e.g. "design the backend API and
            // build the React frontend, and make sure it's cached" - cache
            // is weak-backend but the request is legitimately multi-domain
            // already because of "backend"+"frontend").
            return orderDomains(matched);
        }

        if (strong.size() == 1) {
            // Exactly one domain has real evidence; any other domains that
            // only matched on ambiguous/incidental terms are almost always
            // just vocabulary bleed-through from the one real task, not a
            // second deliverable. Collapse to the single strong domain.
            return orderDomains(strong);
        }

        // No domain has a strong signal - every match is incidental keyword
        // overlap from describing one artifact (e.g. a TypeScript library
        // that happens to mention "SQL-lite", "test suite", and
        // "TypeScript"). Collapse to a single owning domain rather than
        // fanning out three code-writing agents to independently invent
        // three incompatible implementations of the same thing. Priority
        // order matches ORDERED_DOMAINS: backend/frontend do the actual
        // implementation work more often than qa/devops, so prefer them when
        // more than one weak domain matched.
        for (String domain : ORDERED_DOMAINS) {
            if (matched.contains(domain)) {
                return List.of(domain);
            }
        }
        // Unreachable (matched is non-empty and only ever contains
        // ORDERED_DOMAINS members), but keep a safe fallback.
        return List.of("knowledge");
    }

    /** True when the request spans more than one specialist domain. */
    public static boolean isMultiDomain(String request, String context) {
        return detectDomains(request, context).size() > 1;
    }

    private static LinkedHashSet<String> detectLabelledDomains(String combined) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher m = LABEL_PATTERN.matcher(combined);
        while (m.find()) {
            found.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return found;
    }

    private static List<String> orderDomains(Set<String> matched) {
        List<String> ordered = new ArrayList<>();
        for (String domain : ORDERED_DOMAINS) {
            if (matched.contains(domain)) ordered.add(domain);
        }
        return ordered;
    }
}
