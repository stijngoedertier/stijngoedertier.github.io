package be.vlaanderen.omgeving.shaclvalidator;


import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Command-line entry point for the SHACL Validator.
 *
 * <pre>
 * Usage:
 *   java -jar shacl-validator.jar [options] --shapes &lt;shapes-file&gt; --data &lt;data-file&gt;
 *
 * Options:
 *   --shapes  &lt;file&gt;   Path to the RDF file containing the SHACL shapes (required)
 *   --data    &lt;file&gt;   Path to the RDF file containing the data to validate (required)
 *   --output  &lt;file&gt;   Write the raw SHACL report (Turtle) to this file (optional)
 *   --format  text|json Output format for the summary report (default: text)
 *   --strict           Exit with code 1 if there are only warnings/info (default: only errors)
 *   --help             Print this help message
 * </pre>
 */
public class ShaclValidatorCLI {

    private static final String USAGE = """
            SHACL Validator — check RDF data against a SHACL shapes graph
            ----------------------------------------------------------------
            Usage:
              java -jar shacl-validator.jar [options] --shapes <shapes-file> --data <data-file>

            Required:
              --shapes <file>   RDF file containing the SHACL shapes
              --data   <file>   RDF file containing the data to validate

            Optional:
              --output <file>   Write the raw SHACL report (Turtle) to this file
              --format text|json  Summary format (default: text)
              --strict          Exit with code 1 for warnings/info too (not just errors)
              --help            Show this help message

            Supported RDF formats (auto-detected from extension):
              .ttl    Turtle
              .nt     N-Triples
              .nq     N-Quads
              .jsonld JSON-LD
              .rdf    RDF/XML
              .xml    RDF/XML
              .n3     Notation3

            Exit codes:
              0 — data conforms (or only warnings/info in non-strict mode)
              1 — one or more violations found
              2 — usage / IO error
            """;

    public static void main(String[] args) throws Exception {
        // Suppress Jena INFO logging unless the user sets the system property
        if (System.getProperty("log.level") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }

        String shapesFile  = null;
        String dataFile    = null;
        String outputFile  = null;
        String format      = "text";
        boolean strict     = false;

        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--shapes"  -> shapesFile = requireNext(args, i++);
                case "--data"    -> dataFile   = requireNext(args, i++);
                case "--output"  -> outputFile = requireNext(args, i++);
                case "--format"  -> format     = requireNext(args, i++);
                case "--strict"  -> strict     = true;
                case "--help"    -> { System.out.println(USAGE); System.exit(0); }
                default          -> { System.err.println("Unknown option: " + args[i]); System.exit(2); }
            }
        }

        if (shapesFile == null || dataFile == null) {
            System.err.println("ERROR: Both --shapes and --data are required.");
            System.err.println();
            System.err.println(USAGE);
            System.exit(2);
        }

        Path shapesPath = Paths.get(shapesFile);
        Path dataPath   = Paths.get(dataFile);

        if (!Files.exists(shapesPath)) { System.err.println("ERROR: Shapes file not found: " + shapesPath); System.exit(2); }
        if (!Files.exists(dataPath))   { System.err.println("ERROR: Data file not found: "   + dataPath);   System.exit(2); }

        // Run validation
        ShaclValidator validator = new ShaclValidator();
        ValidationResult result;

        try {
            result = validator.validateFiles(shapesPath, dataPath);
        } catch (Exception e) {
            System.err.println("ERROR during validation: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
            return; // unreachable — keeps the compiler happy
        }

        // Print summary
        switch (format.toLowerCase()) {
            case "json" -> System.out.println(ReportFormatter.toJson(result));
            default     -> ReportFormatter.printText(result, System.out);
        }

        // Optionally write the raw Turtle report
        if (outputFile != null) {
            Model shapesModel = ModelFactory.createDefaultModel();
            RDFDataMgr.read(shapesModel, shapesFile);
            Model dataModel = ModelFactory.createDefaultModel();
            RDFDataMgr.read(dataModel, dataFile);

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                validator.writeReport(shapesModel, dataModel, fos);
            }
            System.out.println("Raw SHACL report written to: " + outputFile);
        }

        // Exit code
        boolean hasErrors = result.getViolations().stream()
                .anyMatch(v -> {
                    String s = v.getSeverity();
                    return s == null || s.endsWith("Violation");
                });

        if (hasErrors || (strict && !result.conforms())) {
            System.exit(1);
        }
        System.exit(0);
    }

    private static String requireNext(String[] args, int i) {
        if (i + 1 >= args.length) {
            System.err.println("ERROR: " + args[i] + " requires an argument.");
            System.exit(2);
        }
        return args[i + 1];
    }
}