package be.vlaanderen.omgeving.shaclvalidator;


import java.io.PrintStream;
import java.util.List;

/**
 * Formats a {@link ValidationResult} into human-readable text or JSON.
 */
public class ReportFormatter {

    // -------------------------------------------------------------------------
    // Human-readable text report
    // -------------------------------------------------------------------------

    /**
     * Print a coloured, human-readable summary of the validation result.
     */
    public static void printText(ValidationResult result, PrintStream out) {
        out.println();
        out.println("╔══════════════════════════════════════════════════════════════╗");
        if (result.conforms()) {
            out.println("║          ✓  SHACL VALIDATION PASSED — Data conforms          ║");
        } else {
            out.println("║          ✗  SHACL VALIDATION FAILED — Constraint violations  ║");
        }
        out.println("╚══════════════════════════════════════════════════════════════╝");
        out.println();

        List<ValidationResult.Violation> violations = result.getViolations();

        if (violations.isEmpty()) {
            out.println("  No violations found.");
        } else {
            // Group by severity
            long errors = violations.stream().filter(v -> !isWarningOrInfo(v)).count();
            long warnings = violations.stream().filter(v -> "WARNING".equals(severityLabel(v))).count();
            long infos = violations.stream().filter(v -> "INFO".equals(severityLabel(v))).count();

            out.printf("  Total violations: %d  (Errors: %d, Warnings: %d, Info: %d)%n%n",
                    violations.size(), errors, warnings, infos);

            int i = 1;
            for (ValidationResult.Violation v : violations) {
                out.printf("  %d. [%s]%n", i++, severityLabel(v));
                out.printf("     Focus node : %s%n", v.getFocusNode());
                if (v.getResultPath() != null)
                    out.printf("     Path       : %s%n", v.getResultPath());
                if (v.getValue() != null)
                    out.printf("     Value      : %s%n", v.getValue());
                if (v.getMessage() != null)
                    out.printf("     Message    : %s%n", v.getMessage());
                out.printf("     Constraint : %s%n", v.getSourceConstraint());
                if (v.getSourceShape() != null)
                    out.printf("     Shape      : %s%n", v.getSourceShape());
                out.println();
            }
        }
    }

    // -------------------------------------------------------------------------
    // JSON report (uses minimal hand-crafted JSON to avoid adding Jackson dep)
    // -------------------------------------------------------------------------

    /**
     * Return a JSON string representing the validation result.
     * Built without external JSON libraries to keep the dependency surface small.
     */
    public static String toJson(ValidationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"conforms\": ").append(result.conforms()).append(",\n");
        sb.append("  \"violationCount\": ").append(result.getViolations().size()).append(",\n");
        sb.append("  \"violations\": [\n");

        List<ValidationResult.Violation> violations = result.getViolations();
        for (int i = 0; i < violations.size(); i++) {
            ValidationResult.Violation v = violations.get(i);
            sb.append("    {\n");
            sb.append("      \"severity\": ").append(jsonStr(severityLabel(v))).append(",\n");
            sb.append("      \"focusNode\": ").append(jsonStr(v.getFocusNode())).append(",\n");
            sb.append("      \"resultPath\": ").append(jsonStr(v.getResultPath())).append(",\n");
            sb.append("      \"value\": ").append(jsonStr(v.getValue())).append(",\n");
            sb.append("      \"message\": ").append(jsonStr(v.getMessage())).append(",\n");
            sb.append("      \"sourceConstraint\": ").append(jsonStr(v.getSourceConstraint())).append(",\n");
            sb.append("      \"sourceShape\": ").append(jsonStr(v.getSourceShape())).append("\n");
            sb.append("    }");
            if (i < violations.size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static String severityLabel(ValidationResult.Violation v) {
        String s = v.getSeverity();
        if (s == null) return "VIOLATION";
        if (s.endsWith("Warning")) return "WARNING";
        if (s.endsWith("Info")) return "INFO";
        return "VIOLATION";
    }

    private static boolean isWarningOrInfo(ValidationResult.Violation v) {
        String label = severityLabel(v);
        return "WARNING".equals(label) || "INFO".equals(label);
    }

    private static String jsonStr(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}