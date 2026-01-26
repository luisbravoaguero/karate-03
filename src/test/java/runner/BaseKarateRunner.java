package runner;
import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class BaseKarateRunner {

    protected Results runSuite(String suiteTag, String suiteName) {
        String env = System.getProperty("karate.env", "dev").trim();
        String service = System.getProperty("service", "").trim();      // e.g. postmanEcho
        String rawTags = System.getProperty("karate.tags", "").trim();  // optional extra filters
        int threads = Integer.parseInt(System.getProperty("threads", "5"));

        // Always enforce suite identity: smoke/contract/regression
        List<String> tags = new ArrayList<>();
        tags.add(suiteTag);

        // Optional: allow extra tags like "@login" or "~@wip"
        if (!rawTags.isEmpty()) tags.addAll(splitTags(rawTags));

        // Optional but recommended: enforce service selection
        if (!service.isEmpty()) tags.add("@svc_" + service);

        // Important: keep reports separated per job/env/service (no overwriting)
        String reportDir = "target/karate-reports/" + suiteName + "/" + env + "/" + (service.isEmpty() ? "all" : service);

        return Runner.builder()
                .path("classpath:features/tests")
                .tags(tags.toArray(new String[0]))
                .reportDir(reportDir)
                .outputCucumberJson(true)
                .outputJunitXml(true) // ✅ for Jenkins/Octane
                .parallel(threads);
    }

    private static List<String> splitTags(String raw) {
        return Arrays.stream(raw.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}

