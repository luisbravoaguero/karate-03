package runnersIT;

import com.intuit.karate.Results;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ContractIT extends BaseKarateRunner {
    @Test
    void run() {
        Results results = runSuite("@contract", "contract");
        assertEquals(0, results.getFailCount(), results.getErrorMessages());
    }
}