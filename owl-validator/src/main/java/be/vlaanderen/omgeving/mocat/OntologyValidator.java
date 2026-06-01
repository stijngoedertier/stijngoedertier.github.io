package be.vlaanderen.omgeving.mocat;

import org.semanticweb.HermiT.Configuration;
import org.semanticweb.HermiT.Reasoner;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.*;
import org.semanticweb.owlapi.util.InferredAxiomGenerator;
import org.semanticweb.owlapi.util.InferredOntologyGenerator;
import org.semanticweb.owlapi.util.InferredSubClassAxiomGenerator;
import org.semanticweb.owlapi.util.InferredClassAssertionAxiomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;

/**
 * be.vlaanderen.omgeving.mocat.OntologyValidator
 *
 * <p> Can be used to load one ore more ontologies and validate them by running the HermiT OWL 2 DL reasoner.
 * </p>
 *
 * <p>Validation checks performed:
 * <ul>
 *   <li>Ontology consistency – no logical contradiction exists.</li>
 *   <li>Satisfiability of every named class – no class is forced to be empty.</li>
 *   <li>Instance typing – every named individual is asserted to at least one class.</li>
 * </ul>
 * </p>
 */
public class OntologyValidator {

    private static final Logger LOG = LoggerFactory.getLogger(OntologyValidator.class);

    private final OWLOntologyManager manager;
    private final OWLOntology mergedOntology;

    public OntologyValidator() throws OWLOntologyCreationException {
        this.manager = OWLManager.createOWLOntologyManager();
        IRI mergedIRI = IRI.create("http://example.org/validation/merged");
        this.mergedOntology = manager.createOntology(mergedIRI);
    }

    /**
     * Datatypes present in common RDF vocabularies (DCAT, PROV-O, DC Terms)
     * that are NOT part of the OWL 2 datatype map supported by HermiT.
     * Axioms whose literals use any of these datatypes are removed before
     * the reasoner is initialised.  The structural/class-level axioms that
     * matter for OWL reasoning are unaffected; only annotation-style data
     * property assertions (dates, durations, …) are dropped.
     * <p>
     * OWL 2 datatype map reference:
     * https://www.w3.org/TR/owl2-syntax/#Datatype_Maps
     */
    private static final Set<String> UNSUPPORTED_DATATYPES = new HashSet<>(Arrays.asList(
            "http://www.w3.org/2001/XMLSchema#date",
            "http://www.w3.org/2001/XMLSchema#gYear",
            "http://www.w3.org/2001/XMLSchema#gYearMonth",
            "http://www.w3.org/2001/XMLSchema#gMonth",
            "http://www.w3.org/2001/XMLSchema#gMonthDay",
            "http://www.w3.org/2001/XMLSchema#gDay",
            "http://www.w3.org/2001/XMLSchema#duration",
            "http://www.w3.org/2001/XMLSchema#NOTATION",
            "http://www.w3.org/2001/XMLSchema#QName"
    ));


    // ── Ontology loading ──────────────────────────────────────────────────────

    /**
     * Loads a single Turtle file from the classpath (with optional remote
     * fallback) and copies all its axioms into the merged ontology.
     */
    public void loadAndMerge(String label,
                             String classpathResource,
                             String remoteFallbackUrl)
            throws OWLOntologyCreationException {

        OWLOntology source = null;

        // Try classpath first
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream(classpathResource)) {

            if (is != null) {
                OWLOntologyDocumentSource docSource =
                        new StreamDocumentSource(is,
                                IRI.create("classpath://" + classpathResource),
                                null,
                                null);
                source = manager.loadOntologyFromOntologyDocument(docSource);
                LOG.info("[{}] Loaded from classpath: {}", label, classpathResource);
            }
        } catch (Exception e) {
            LOG.warn("[{}] Classpath load failed ({}), trying remote …", label, e.getMessage());
        }

        // Remote fallback
        if (source == null && remoteFallbackUrl != null) {
            LOG.info("[{}] Loading from remote: {}", label, remoteFallbackUrl);
            source = manager.loadOntology(IRI.create(remoteFallbackUrl));
        }

        if (source == null) {
            throw new OWLOntologyCreationException(
                    "Could not load ontology [" + label + "] from classpath or remote.");
        }

        // Copy all axioms (including from imports) into the merged ontology
        List<OWLOntologyChange> changes = new ArrayList<>();
        for (OWLAxiom axiom : source.getAxioms(Imports.INCLUDED)) {
            changes.add(new AddAxiom(mergedOntology, axiom));
        }
        manager.applyChanges(changes);
        LOG.info("[{}] {} axioms merged.", label,
                source.getAxiomCount(Imports.INCLUDED));
    }

    // ── Datatype stripping ────────────────────────────────────────────────────

    /**
     * Removes every axiom from merged ontology whose literals reference a
     * datatype not supported by HermiT (i.e. absent from the OWL 2 datatype
     * map).  Returns the number of axioms removed.
     *
     * <p>Strategy: walk every axiom, collect all literals via the visitor,
     * and remove the axiom if any literal's datatype is in the block-list.
     */
    public int stripUnsupportedDatatypeAxioms() {
        List<OWLOntologyChange> removals = new ArrayList<>();

        for (OWLAxiom axiom : mergedOntology.getAxioms(Imports.EXCLUDED)) {
            if (axiomContainsUnsupportedDatatype(axiom)) {
                removals.add(new RemoveAxiom(mergedOntology, axiom));
                LOG.debug("Stripping axiom with unsupported datatype: {}", axiom);
            }
        }

        manager.applyChanges(removals);
        return removals.size();
    }

    /**
     * Returns true if the axiom contains at least one literal with an
     * unsupported datatype.
     */
    private boolean axiomContainsUnsupportedDatatype(OWLAxiom axiom) {
        return axiom.components()
                .filter(c -> c instanceof OWLLiteral)
                .map(c -> (OWLLiteral) c)
                .anyMatch(lit ->
                        UNSUPPORTED_DATATYPES.contains(
                                lit.getDatatype().getIRI().toString()));
    }


    // ── Validation logic ──────────────────────────────────────────────────────

    public ValidationReport validate() {
        LOG.info("Initialising HermiT OWL 2 DL reasoner …");
        Configuration cfg = new Configuration();
        cfg.reasonerProgressMonitor = new ConsoleProgressMonitor();
        OWLReasoner reasoner = new Reasoner(cfg, mergedOntology);

        ValidationReport report = new ValidationReport();

        try {
            // ── Check 1: Consistency ──────────────────────────────────────────────
            LOG.info("Running consistency check …");
            report.isConsistent = reasoner.isConsistent();
            if (!report.isConsistent) {
                // If the ontology is inconsistent we cannot trust further results
                LOG.warn("Ontology is INCONSISTENT. Skipping further checks.");
                return report;
            }
            LOG.info("Consistency check passed.");

            // ── Check 2: Class satisfiability ────────────────────────────────────
            LOG.info("Checking class satisfiability …");
            OWLDataFactory df = manager.getOWLDataFactory();

            for (OWLClass cls : mergedOntology.getClassesInSignature(Imports.INCLUDED)) {
                if (cls.isOWLThing() || cls.isOWLNothing()) continue;

                if (!reasoner.isSatisfiable(cls)) {
                    report.unsatisfiableClasses.add(cls.getIRI().getShortForm()
                            + "  <" + cls.getIRI() + ">");
                }
            }
            LOG.info("Satisfiability check complete. Unsatisfiable classes found: {}",
                    report.unsatisfiableClasses.size());

            // ── Check 3: Individual typing ────────────────────────────────────────
            LOG.info("Checking individual typing …");
            for (OWLNamedIndividual ind :
                    mergedOntology.getIndividualsInSignature(Imports.INCLUDED)) {

                Set<OWLClass> types = new HashSet<>();
                for (OWLClassExpression ce : EntitySearcher
                        .getTypes(ind, mergedOntology.getImportsClosure().stream())) {
                    if (!ce.isAnonymous()) {
                        types.add(ce.asOWLClass());
                    }
                }
                if (types.isEmpty()) {
                    report.untypedIndividuals.add(ind.getIRI().getShortForm()
                            + "  <" + ind.getIRI() + ">");
                }
            }
            LOG.info("Individual typing check complete. Untyped individuals: {}",
                    report.untypedIndividuals.size());

            // ── Check 4: Inferred class hierarchy (informational) ─────────────────
            LOG.info("Extracting inferred sub-class relationships …");
            List<InferredAxiomGenerator<? extends OWLAxiom>> generators = new ArrayList<>();
            generators.add(new InferredSubClassAxiomGenerator());
            generators.add(new InferredClassAssertionAxiomGenerator());

            try {
                OWLOntology inferredOntology =
                        manager.createOntology(
                                IRI.create("http://example.org/validation/inferred"));

                new InferredOntologyGenerator(reasoner, generators)
                        .fillOntology(df, inferredOntology);

                report.inferredAxiomCount = inferredOntology.getAxiomCount();
                LOG.info("Inferred axioms generated: {}", report.inferredAxiomCount);

            } catch (OWLOntologyCreationException e) {
                LOG.warn("Could not create inferred ontology: {}", e.getMessage());
            }
        } finally {
            reasoner.dispose();
        }

        return report;
    }

    // ── Report printing ───────────────────────────────────────────────────────

    public void printReport(ValidationReport r) {
        System.out.println();
        printBanner("VALIDATION REPORT");

        printSection("1. Consistency");
        if (r.isConsistent) {
            pass("Ontology is consistent – no logical contradictions detected.");
        } else {
            fail("Ontology is INCONSISTENT! The combined axioms lead to a logical contradiction.");
            System.out.println("     Tip: check for disjoint-class violations, unsatisfiable property chains,");
            System.out.println("          or contradictory range/domain restrictions.");
        }

        if (r.isConsistent) {
            printSection("2. Class Satisfiability");
            if (r.unsatisfiableClasses.isEmpty()) {
                pass("All named classes are satisfiable.");
            } else {
                fail(r.unsatisfiableClasses.size() + " unsatisfiable class(es) found:");
                r.unsatisfiableClasses.forEach(c -> System.out.println("     ✗  " + c));
                System.out.println("     Tip: unsatisfiable classes usually result from conflicting disjointness");
                System.out.println("          declarations or contradictory property restrictions.");
            }

            printSection("3. Individual Typing");
            if (r.untypedIndividuals.isEmpty()) {
                pass("All named individuals have at least one explicit class assertion.");
            } else {
                warn(r.untypedIndividuals.size() + " individual(s) without an explicit rdf:type:");
                r.untypedIndividuals.forEach(i -> System.out.println("     ⚠  " + i));
            }

            printSection("4. Inferred Axioms (informational)");
            System.out.printf("   ℹ  HermiT inferred %d new axioms (subclass + type assertions).%n",
                    r.inferredAxiomCount);
        }

        System.out.println();
        printBanner(r.isConsistent && r.unsatisfiableClasses.isEmpty()
                ? "RESULT:  ✅  VALIDATION PASSED"
                : "RESULT:  ❌  VALIDATION FAILED");
        System.out.println();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public void printBanner(String text) {
        String bar = "═".repeat(text.length() + 4);
        System.out.println("╔" + bar + "╗");
        System.out.println("║  " + text + "  ║");
        System.out.println("╚" + bar + "╝");
    }

    private void printSection(String title) {
        System.out.println("\n── " + title + " " + "─".repeat(50 - title.length()));
    }

    private void pass(String msg) {
        System.out.println("   ✅  " + msg);
    }

    private void fail(String msg) {
        System.out.println("   ❌  " + msg);
    }

    private void warn(String msg) {
        System.out.println("   ⚠️  " + msg);
    }

    /**
     * Best-effort log-level suppression without forcing a specific SLF4J backend.
     */
    public void setLogLevel(String pkg, String level) {
        // When using slf4j-simple the level is controlled by system properties.
        // This is a no-op for other backends; configure logback/log4j externally.
        System.setProperty("org.slf4j.simpleLogger.log." + pkg, level.toLowerCase());
    }

    // ── Inner classes ─────────────────────────────────────────────────────────

    public OWLOntology getMergedOntology() {
        return mergedOntology;
    }

    /**
     * Accumulates validation findings.
     */
    public static class ValidationReport {
        public boolean isConsistent = false;
        public List<String> unsatisfiableClasses = new ArrayList<>();
        public List<String> untypedIndividuals = new ArrayList<>();
        public int inferredAxiomCount = 0;
    }

    /**
     * Prints HermiT reasoning progress to stdout.
     */
    private static class ConsoleProgressMonitor implements ReasonerProgressMonitor {
        @Override
        public void reasonerTaskStarted(String taskName) {
            LOG.info("  [HermiT] Starting: {}", taskName);
        }

        @Override
        public void reasonerTaskStopped() {
            LOG.info("  [HermiT] Done.");
        }

        @Override
        public void reasonerTaskProgressChanged(int value, int max) { /* quiet */ }

        @Override
        public void reasonerTaskBusy() { /* quiet */ }
    }

    /**
     * Thin utility – mirrors the OWL-API static {@code EntitySearcher} usage
     * available in owlapi 4.x.
     */
    private static class EntitySearcher {
        static Iterable<OWLClassExpression> getTypes(OWLNamedIndividual ind,
                                                     java.util.stream.Stream<OWLOntology> ontologies) {
            List<OWLClassExpression> types = new ArrayList<>();
            ontologies.forEach(ont ->
                    ont.getClassAssertionAxioms(ind)
                            .forEach(ax -> types.add(ax.getClassExpression())));
            return types;
        }
    }
}
