package be.vlaanderen.omgeving.shaclvalidator;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds the outcome of a SHACL validation run.
 */
public class ValidationResult {

    private final boolean conforms;
    private final List<Violation> violations;

    public ValidationResult(boolean conforms, List<Violation> violations) {
        this.conforms = conforms;
        this.violations = Collections.unmodifiableList(new ArrayList<>(violations));
    }

    /**
     * @return true if the data graph fully conforms to all shapes.
     */
    public boolean conforms() {
        return conforms;
    }

    /**
     * @return an immutable list of all constraint violations (empty when conformant).
     */
    public List<Violation> getViolations() {
        return violations;
    }

    // -------------------------------------------------------------------------

    /**
     * Represents a single sh:ValidationResult from the SHACL report.
     */
    public static class Violation {

        private final String severity;        // e.g. sh:Violation / sh:Warning / sh:Info
        private final String focusNode;       // the offending resource URI or blank-node ID
        private final String resultPath;      // the property path that failed (may be null)
        private final String sourceConstraint;// sh:sourceConstraintComponent
        private final String sourceShape;     // sh:sourceShape
        private final String message;         // sh:resultMessage (may be null)
        private final String value;           // sh:value (may be null)

        public Violation(String severity, String focusNode, String resultPath,
                         String sourceConstraint, String sourceShape,
                         String message, String value) {
            this.severity = severity;
            this.focusNode = focusNode;
            this.resultPath = resultPath;
            this.sourceConstraint = sourceConstraint;
            this.sourceShape = sourceShape;
            this.message = message;
            this.value = value;
        }

        public String getSeverity() {
            return severity;
        }

        public String getFocusNode() {
            return focusNode;
        }

        public String getResultPath() {
            return resultPath;
        }

        public String getSourceConstraint() {
            return sourceConstraint;
        }

        public String getSourceShape() {
            return sourceShape;
        }

        public String getMessage() {
            return message;
        }

        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(severityLabel()).append("] ");
            sb.append("Focus node: ").append(focusNode);
            if (resultPath != null) sb.append(" | Path: ").append(resultPath);
            if (value != null) sb.append(" | Value: ").append(value);
            if (message != null) sb.append(" | Message: ").append(message);
            sb.append(" | Constraint: ").append(sourceConstraint);
            if (sourceShape != null) sb.append(" | Shape: ").append(sourceShape);
            return sb.toString();
        }

        private String severityLabel() {
            if (severity == null) return "VIOLATION";
            if (severity.endsWith("Warning")) return "WARNING";
            if (severity.endsWith("Info")) return "INFO";
            return "VIOLATION";
        }
    }
}