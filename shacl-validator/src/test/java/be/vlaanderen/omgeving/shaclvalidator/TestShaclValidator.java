package be.vlaanderen.omgeving.shaclvalidator;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TestShaclValidator {
    private static final Logger LOG = LoggerFactory.getLogger(TestShaclValidator.class);

    @Test
    public void test() {
        LOG.info("Test");
        Path shapesPath = Paths.get("src/test/resources/transportNetworkSchema.ttl");
        Path dataPath = Paths.get("src/test/resources/transportNetworkData.ttl");

        if (!Files.exists(shapesPath)) {
            System.err.println("ERROR: Shapes file not found: " + shapesPath);
            System.exit(2);
        }
        if (!Files.exists(dataPath)) {
            System.err.println("ERROR: Data file not found: " + dataPath);
            System.exit(2);
        }

        // Run validation
        ShaclValidator validator = new ShaclValidator();
        ValidationResult result;

        try {
            result = validator.validateFiles(shapesPath, dataPath);
        } catch (Exception e) {
            System.err.println("ERROR during validation: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }
}
