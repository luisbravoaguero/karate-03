package runnersIT;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;

import java.util.ArrayList;
import java.util.List;

public abstract class BaseKarateRunner {

    protected Results runSuite(String suiteTag, String suiteName) {
        String env = System.getProperty("karate.env", "dev").trim();
        String service = System.getProperty("service", "").trim();
        String extraExpr = System.getProperty("karate.tags", "").trim();
        int threads = Integer.parseInt(System.getProperty("threads", "5"));

        // Enforce service always (align with karate-config fail fast)
        if (service.isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing -Dservice. Example: -Dservice=dummyjson"
            );
        }

        List<String> tags = new ArrayList<>();
        tags.add(suiteTag);            // e.g. @smoke
        tags.add("@svc_" + service);   // e.g. @svc_dummyjson

        // IMPORTANT: keep extra tag expression as a single expression string
        // so users can use: "~@wip and ~@quarantine"
        if (!extraExpr.isEmpty()) {
            tags.add(extraExpr);
        }

        String reportDir = "target/karate-reports/" + suiteName + "/" + env + "/" + service;

        return Runner.builder()
                .path("classpath:features/tests")
                .tags(tags.toArray(new String[0]))
                .reportDir(reportDir)
                .outputCucumberJson(true)
                .outputJunitXml(true)
                .parallel(threads);
    }
}
