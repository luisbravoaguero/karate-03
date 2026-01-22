package com.mapfre.karate.runner;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import com.intuit.karate.Suite;
import com.intuit.karate.core.Scenario;
import com.intuit.karate.core.ScenarioResult;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
public class CustomParallelTest {
    private static final String INTERNAL_CONSOLE_LOG = "target/karate-internal-console.log";

    @Test
    void runInParallelWithCleanConsoleLogAndRetries() throws Exception {
        // Keep a reference to the real console (Jenkins / IntelliJ)
        PrintStream consoleOut = System.out;
        PrintStream consoleErr = System.err;

        String env = System.getProperty("karate.env", "dev");
        String service = System.getProperty("service", "").trim(); // ex: postmanEcho
        int threads = Integer.parseInt(System.getProperty("threads", "5"));
        int retries = Integer.parseInt(System.getProperty("retries", "2"));
        String rawTags = System.getProperty("karate.tags", "").trim();

        List<String> tags = new ArrayList<>();
        if (!rawTags.isEmpty()) tags.addAll(splitTags(rawTags));
        if (!service.isEmpty()) tags.add("@svc_" + service);

        String started = OffsetDateTime.now().toString();

        // Manager-friendly header (console)
        consoleOut.println("[KARATE] RUN | env=" + env
                + " | svc=" + (service.isEmpty() ? "(all)" : service)
                + " | tags=" + (tags.isEmpty() ? "(none)" : String.join(",", tags))
                + " | threads=" + threads
                + " | retries=" + retries
                + " | started=" + started);

        Results results;
        Map<String, Integer> retryCountByScenarioId = new HashMap<>();

        // Redirect Karate’s own console spam to a file (during execution only)
        try (PrintStream karateInternal = new PrintStream(new FileOutputStream(INTERNAL_CONSOLE_LOG, true), true, "UTF-8")) {
            System.setOut(karateInternal);
            System.setErr(karateInternal);

            // Run tests (Karate internal output goes to file now)
            results = Runner.builder()
                    .path("classpath:features/tests")     // IMPORTANT: only scan tests folder
                    .tags(tags.toArray(new String[0]))    // smoke + service tag
                    .reportDir("target/karate-reports")
                    .outputCucumberJson(true)
                    .parallel(threads);

            // Build stable scenario numbering from first run
            Map<String, Integer> scnIndex = buildScenarioIndex(results);

            // Retry only failed scenarios (still with Karate output suppressed)
            if (retries > 0 && results.getFailCount() > 0) {
                results = retryFailedScenarios(results, retries, env, service, scnIndex, retryCountByScenarioId, consoleOut);
            }

        } finally {
            // Restore console no matter what
            System.setOut(consoleOut);
            System.setErr(consoleErr);
        }

        // Final per-scenario lines (console)
        List<ScenarioResult> finals = results.getScenarioResults()
                .sorted()
                .collect(Collectors.toList());

        // Rebuild numbering from final results (keeps PASS/FAIL list consistent)
        Map<String, Integer> finalIndex = buildScenarioIndexFromList(finals);

        for (ScenarioResult sr : finals) {
            Scenario sc = sr.getScenario();
            String id = sc.getUniqueId();
            int scn = finalIndex.getOrDefault(id, 0);
            int retried = retryCountByScenarioId.getOrDefault(id, 0);

            if (sr.isFailed()) {
                consoleOut.println(String.format(
                        "[SCN %02d] FAIL  | %.2fs | env=%s | svc=%s | SCN=\"%s\" | retried=%d | see report",
                        scn, sr.getDurationMillis() / 1000.0, env, safeSvc(service), safeName(sc.getName()), retried
                ));
                // Print the full error ONCE (friendly)
                consoleOut.println("         error: " + safeOneLine(sr.getFailureMessageForDisplay()));
                consoleOut.println("         report: target/karate-reports/karate-summary.html");
            } else {
                String src = shortSrc(sc);

                consoleOut.println(String.format(
                        "[SCN %02d] PASS  | %.2fs | env=%s | svc=%s | src=%s | SCN=\"%s\"%s",
                        scn, sr.getDurationMillis() / 1000.0, env, safeSvc(service), src, safeName(sc.getName()),
                        retried > 0 ? " | (passed after retries)" : ""
                ));
            }
        }

        long retriedScenarios = retryCountByScenarioId.values().stream().filter(v -> v > 0).count();
        int totalRetries = retryCountByScenarioId.values().stream().mapToInt(Integer::intValue).sum();

        consoleOut.println(String.format(
                "[KARATE] SUMMARY | env=%s | svc=%s | total=%d | passed=%d | failed=%d | retriedScenarios=%d | totalRetries=%d | time=%.2fs | reportDir=%s",
                env, safeSvc(service),
                results.getScenariosTotal(), results.getScenariosPassed(), results.getScenariosFailed(),
                retriedScenarios, totalRetries,
                results.getTimeTakenMillis() / 1000.0,
                results.getReportDir()
        ));

        // Short JUnit failure message (prevents huge duplicate output)
        assertEquals(0, results.getFailCount(),
                "There are " + results.getFailCount() + " failing scenario(s). Open: target/karate-reports/karate-summary.html");
    }

    // ----------------- Retries (short retry logs) -----------------

    private static Results retryFailedScenarios(
            Results results,
            int maxRetries,
            String env,
            String service,
            Map<String, Integer> scnIndex,
            Map<String, Integer> retryCountByScenarioId,
            PrintStream consoleOut
    ) {
        Suite suite = results.getSuite();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            List<ScenarioResult> failed = results.getScenarioResults()
                    .filter(ScenarioResult::isFailed)
                    .collect(Collectors.toList());

            if (failed.isEmpty()) break;

            for (ScenarioResult sr : failed) {
                Scenario sc = sr.getScenario();
                String id = sc.getUniqueId();
                int scn = scnIndex.getOrDefault(id, 0);

                retryCountByScenarioId.put(id, retryCountByScenarioId.getOrDefault(id, 0) + 1);

                // SHORT reason (no long match lines)
                String reason = shortReason(sr.getFailureMessageForDisplay());

                String src = shortSrc(sc);

                consoleOut.println(String.format(
                        "[SCN %02d] RETRY | %d/%d | env=%s | svc=%s | src=%s | SCN=\"%s\" | reason=%s",
                        scn, attempt, maxRetries, env, safeSvc(service), src, safeName(sc.getName()), reason
                ));

                ScenarioResult rerun = suite.retryScenario(sc);
                results = suite.updateResults(rerun);
            }
        }
        return results;
    }

    // ----------------- Helpers -----------------

    private static List<String> splitTags(String raw) {
        return Arrays.stream(raw.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static Map<String, Integer> buildScenarioIndex(Results results) {
        List<ScenarioResult> list = results.getScenarioResults().sorted().collect(Collectors.toList());
        return buildScenarioIndexFromList(list);
    }

    private static Map<String, Integer> buildScenarioIndexFromList(List<ScenarioResult> list) {
        Map<String, Integer> idx = new HashMap<>();
        int i = 1;
        for (ScenarioResult sr : list) {
            idx.put(sr.getScenario().getUniqueId(), i++);
        }
        return idx;
    }


    private static String shortSrc(Scenario sc) {
        // Example: "classpath:features/tests/postmanEcho/echo-smoke-10.feature"
        String uri = sc.getUriToLineNumber().toString().replace('\\', '/');

        // Normalize prefix
        if (uri.startsWith("classpath:")) {
            uri = uri.substring("classpath:".length());
        }

        // Remove query part if any (e.g., "?line=...")
        int q = uri.indexOf('?');
        if (q > -1) uri = uri.substring(0, q);

        // Keep only the last 3 segments: tests/<parent>/<file.feature>
        String[] parts = uri.split("/");
        if (parts.length <= 3) return String.join("/", parts);

        return parts[parts.length - 3] + "/" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }


    private static String safeSvc(String service) {
        return (service == null || service.isBlank()) ? "(all)" : service;
    }

    private static String safeName(String name) {
        return name == null ? "" : name.replace("\"", "'");
    }

    private static String safeOneLine(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String shortReason(String msg) {
        String m = safeOneLine(msg).toLowerCase(Locale.ROOT);

        if (m.contains("timeout")) return "timeout (see report)";
        if (m.contains("http 5") || m.contains("http 4")) return "http error (see report)";
        if (m.contains("match failed") || m.contains("assert")) return "assertion failed (see report)";
        return "failed (see report)";
    }
}
