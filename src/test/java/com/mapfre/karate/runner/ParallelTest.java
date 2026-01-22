package com.mapfre.karate.runner;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import com.intuit.karate.Suite;
import com.intuit.karate.core.Scenario;
import com.intuit.karate.core.ScenarioResult;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ParallelTest {

    @Test
    void runInParallelWithCleanConsoleLogAndRetries() {
        String env = System.getProperty("karate.env", "dev");
        String service = System.getProperty("service", "").trim();  // e.g. postmanEcho
        int threads = Integer.parseInt(System.getProperty("threads", "5"));
        int retries = Integer.parseInt(System.getProperty("retries", "2")); // set 0 to disable
        String rawTags = System.getProperty("karate.tags", "").trim();       // e.g. @smoke

        String runId = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        List<String> tags = new ArrayList<>();
        if (!rawTags.isEmpty()) tags.addAll(splitTags(rawTags));
        if (!service.isEmpty()) tags.add("@svc_" + service);

        logStart(runId, env, service, threads, retries, tags);

        Results results = Runner.builder()
                .path("classpath:features/tests")
                .tags(tags.toArray(new String[0]))
                .reportDir("target/karate-reports")
                .outputCucumberJson(true)
                .parallel(threads);

        Map<String, Integer> retryCountByScenarioId = new HashMap<>();
        if (retries > 0 && results.getFailCount() > 0) {
            results = retryFailedScenarios(results, retries, env, service, retryCountByScenarioId);
        }

        // Print final per-scenario lines (clean, one line each)
        List<ScenarioResult> finalScenarioResults = results.getScenarioResults()
                .sorted()
                .collect(Collectors.toList());

        for (ScenarioResult sr : finalScenarioResults) {
            Scenario sc = sr.getScenario();
            String id = sc.getUniqueId();
            int retried = retryCountByScenarioId.getOrDefault(id, 0);
            logScenarioFinal(sr, env, service, retried);
        }

        logSummary(results, env, service, retryCountByScenarioId);

        // Make Maven/Surefire fail the build if anything is still failing
        assertEquals(0, results.getFailCount(), results.getErrorMessages());
    }

    private static List<String> splitTags(String raw) {
        // supports "@smoke,@regression" OR "@smoke @regression"
        return Arrays.stream(raw.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static Results retryFailedScenarios(Results results,
                                                int maxRetries,
                                                String env,
                                                String service,
                                                Map<String, Integer> retryCountByScenarioId) {

        // We retry scenarios using the Suite retry framework
        Suite suite = results.getSuite();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            List<ScenarioResult> failed = results.getScenarioResults()
                    .filter(ScenarioResult::isFailed)
                    .collect(Collectors.toList());

            if (failed.isEmpty()) break;

            for (ScenarioResult sr : failed) {
                Scenario sc = sr.getScenario();
                String id = sc.getUniqueId();

                retryCountByScenarioId.put(id, retryCountByScenarioId.getOrDefault(id, 0) + 1);

                String reason = safeOneLine(sr.getFailureMessageForDisplay());
                logRetry(attempt, maxRetries, env, service, sc, reason);

                ScenarioResult rerun = suite.retryScenario(sc);
                results = suite.updateResults(rerun);
            }
        }
        return results;
    }

    // ---------- Logging helpers (manager-friendly) ----------

    private static void logStart(String runId, String env, String service, int threads, int retries, List<String> tags) {
        System.out.println("[KARATE] START"
                + " | runId=" + runId
                + " | env=" + env
                + " | service=" + (service.isEmpty() ? "(all)" : service)
                + " | tags=" + (tags.isEmpty() ? "(none)" : String.join(",", tags))
                + " | threads=" + threads
                + " | retries=" + retries);
    }

    private static void logRetry(int attempt, int max, String env, String service, Scenario sc, String reason) {
        System.out.println("[KARATE] RETRY " + attempt + "/" + max
                + " | env=" + env
                + " | service=" + (service.isEmpty() ? "(all)" : service)
                + " | " + sc.getUriToLineNumber()
                + " | " + safeOneLine(sc.getName())
                + " | reason=" + reason);
    }

    private static void logScenarioFinal(ScenarioResult sr, String env, String service, int retried) {
        Scenario sc = sr.getScenario();
        String status = sr.isFailed() ? "FAIL" : "PASS";
        String time = formatSeconds(sr.getDurationMillis());
        String retryInfo = (retried > 0) ? (" | retried=" + retried) : "";

        String extra = "";
        if (sr.isFailed()) {
            extra = " | error=" + safeOneLine(sr.getFailureMessageForDisplay());
        }

        System.out.println("[KARATE] " + status
                + " | env=" + env
                + " | service=" + (service.isEmpty() ? "(all)" : service)
                + " | " + time
                + " | " + sc.getUriToLineNumber()
                + " | " + safeOneLine(sc.getName())
                + retryInfo
                + extra);
    }

    private static void logSummary(Results results, String env, String service, Map<String, Integer> retryCountByScenarioId) {
        long retriedScenarios = retryCountByScenarioId.entrySet().stream().filter(e -> e.getValue() > 0).count();

        System.out.println("[KARATE] SUMMARY"
                + " | env=" + env
                + " | service=" + (service.isEmpty() ? "(all)" : service)
                + " | total=" + results.getScenariosTotal()
                + " | passed=" + results.getScenariosPassed()
                + " | failed=" + results.getScenariosFailed()
                + " | retriedScenarios=" + retriedScenarios
                + " | time=" + formatSeconds(results.getTimeTakenMillis())
                + " | reportDir=" + results.getReportDir());
    }

    private static String formatSeconds(double millis) {
        double seconds = millis / 1000.0;
        return String.format(Locale.US, "%.2fs", seconds);
    }

    private static String safeOneLine(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }
}
