package runner;
import com.intuit.karate.Results;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SmokeTest extends BaseKarateRunner {

    @Test
    void run() {
        Results results = runSuite("@smoke", "smoke");
        assertEquals(0, results.getFailCount(), results.getErrorMessages());
    }
}
