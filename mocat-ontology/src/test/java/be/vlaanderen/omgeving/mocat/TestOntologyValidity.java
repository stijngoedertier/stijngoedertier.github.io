package be.vlaanderen.omgeving.mocat;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ValidateOntology test class.
 *
 * <p>Uses {@link be.vlaanderen.omgeving.mocat.OntologyValidator} to validate.
 *
 * <p>Validation checks performed:
 * <ul>
 *   <li>Ontology consistency – no logical contradiction exists.</li>
 *   <li>Satisfiability of every named class – no class is forced to be empty.</li>
 *   <li>Instance typing – every named individual is asserted to at least one class.</li>
 * </ul>
 */
public class TestOntologyValidity {

    private static final Logger LOG = LoggerFactory.getLogger(TestOntologyValidity.class);

    // ── Local resource paths (on the classpath under resources/ontologies/) ──
    private static final String DCAT_TTL = "ontologies/dcat3.ttl";
    private static final String PROVO_TTL = "ontologies/prov-o.ttl";
    private static final String PPLAN_TTL = "ontologies/p-plan.owl";
    private static final String SHACL_TTL = "ontologies/shacl.ttl";
    private static final String CUSTOM_ONT_TTL = "ontologies/mocat-ontology.ttl";
    private static final String EXAMPLE_DATA_TTL = "ontologies/mocat-example.ttl";

    // ── Remote fallback URLs (used when local copies are absent) ─────────────
    private static final String DCAT_URL = "https://www.w3.org/ns/dcat3.ttl";
    private static final String PROVO_URL = "https://www.w3.org/ns/prov-o-inverses.ttl";

    @Test
    public void validateOntology() {
        try {
            OntologyValidator validator = new OntologyValidator();

            validator.setLogLevel("org.semanticweb", "WARN");
            validator.setLogLevel("uk.ac.manchester", "WARN");

            validator.printBanner("OWL Ontology Validator");

            // ── 1. Build a merged ontology ────────────────────────────────────
            validator.loadAndMerge("DCAT", DCAT_TTL, DCAT_URL);
            validator.loadAndMerge("PROV-O", PROVO_TTL, PROVO_URL);
            validator.loadAndMerge("P-Plan", PPLAN_TTL, null);
            //validator.loadAndMerge("SHACL", SHACL_TTL, null);
            validator.loadAndMerge("MocatOntology", CUSTOM_ONT_TTL, null);
            validator.loadAndMerge("MocatExample", EXAMPLE_DATA_TTL, null);

            int axiomCount = validator.getMergedOntology().getAxiomCount(Imports.INCLUDED);
            LOG.info("Merged ontology loaded. Total axioms (incl. imports): {}", axiomCount);

            // ── 2. Strip axioms with HermiT-unsupported datatypes ─────────────
            int stripped = validator.stripUnsupportedDatatypeAxioms();
            if (stripped > 0) {
                LOG.info("Stripped {} axiom(s) with HermiT-unsupported datatypes " + "(xsd:date, xsd:gYear, etc.) — structural reasoning unaffected.", stripped);
            }

            // ── 3. Run validation ─────────────────────────────────────────────
            OntologyValidator.ValidationReport report = validator.validate();

            // ── 4. Print report ───────────────────────────────────────────────
            validator.printReport(report);

            assertTrue(report.isConsistent, "Ontology must be consistent");
            assertTrue(report.unsatisfiableClasses.isEmpty(), "There should be no unsatisfiable classes");

        } catch (Exception e) {
            LOG.error("Fatal error during validation: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
