package be.vlaanderen.omgeving.mocat;

import be.vlaanderen.omgeving.rdfvalidator.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.JavaConverters;

import java.io.File;
import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CompleteValidationTest {

    private static final Logger LOG = LoggerFactory.getLogger(CompleteValidationTest.class);

    private static final Property OWL_MEMBERS =
            ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#members");

    private Model completeOntology;
    private Shapes generatedShaclShapes;
    private Reasoner owlReasonerWithSchema;
    private GenericRuleReasoner ruleReasoner;
    private List<File> exampleFiles;

    @BeforeAll
    void setUp() throws URISyntaxException {
        // Mirrors OntologySorter.completeOntology: alle TTL uit ontologies/ samenvoegen
        completeOntology = ModelFactory.createDefaultModel();
        for (File f : javaFiles("ontologies")) {
            LOG.info("Loading ontology: {}", f.getName());
            completeOntology.add(RdfUtils.parseTurtle(f));
        }

        // Mirrors OntologySorter.disjointSubset
        Model disjointSubset = OntologySubsets.extractDisjointSubset(completeOntology);

        // SHACL genereren vanuit OWL-restricties
        Model shaclModel = OwlToShaclGenerator.generate(completeOntology);
        generatedShaclShapes = Shapes.parse(shaclModel);

        // OWL Mini Reasoner gebonden aan het disjoint-subset
        owlReasonerWithSchema = ReasonerRegistry.getOWLMiniReasoner().bindSchema(disjointSubset);

        // GenericRuleReasoner met domain/range/subproperty regels
        String rulesUrl = getClass().getClassLoader()
                .getResource("rules/domain-range-subproperty.rules").toString();
        ruleReasoner = new GenericRuleReasoner(Rule.rulesFromURL(rulesUrl));
        ruleReasoner.setDerivationLogging(false);

        exampleFiles = javaFiles("examples");
    }

    // ── 1. Properties in voorbeelden aanwezig in ontologieën ─────────────────
    //    Controleert dat elk predicaat dat in een voorbeeld gebruikt wordt ook
    //    als resource voorkomt in de gecombineerde ontologie.

    @Test
    void eachExampleUsesOnlyKnownProperties() {
        for (File file : exampleFiles) {
            Model dataModel = RdfUtils.parseTurtle(file);
            ValidationResult result = RdfUtils.checkVocabularyUsage(dataModel, completeOntology);
            List<String> msgs = JavaConverters.seqAsJavaList(result.messages());
            msgs.forEach(m -> LOG.warn("❌ [VOCAB ERROR] {}: {}", file.getName(), m));
            assertTrue(result.valid(),
                    "Onbekende properties/klassen in " + file.getName() + ":\n"
                            + String.join("\n", msgs));
        }
    }

    // ── 2. Class disjointness na inferentie ──────────────────────────────────
    //    Leidt eerst nieuwe type-asserties af via domain/range/subclass-regels,
    //    dan controleert het of een resource tegelijk tot twee disjuncte klassen
    //    behoort (owl:AllDisjointClasses én pairwise owl:disjointWith).

    @Test
    void eachExampleRespectsClassDisjointness() {
        for (File file : exampleFiles) {
            Model inferred = inferTriples(RdfUtils.parseTurtle(file));
            List<String> violations = checkDisjointness(inferred, completeOntology);
            violations.forEach(v -> LOG.warn("❌ [DISJOINT] {}: {}", file.getName(), v));
            assertTrue(violations.isEmpty(),
                    "Disjointness-overtredingen in " + file.getName() + ":\n"
                            + String.join("\n", violations));
        }
    }

    // ── 3. OWL validatie op het afgeleide model ──────────────────────────────

    @Test
    void eachExamplePassesOwlValidation() {
        for (File file : exampleFiles) {
            Model inferred = inferTriples(RdfUtils.parseTurtle(file));
            ValidationResult result = RdfUtils.validateModel(inferred, owlReasonerWithSchema);
            List<String> msgs = JavaConverters.seqAsJavaList(result.messages());
            msgs.forEach(m -> LOG.warn("❌ [OWL] {}: {}", file.getName(), m));
            assertTrue(result.valid(),
                    "OWL-overtredingen in " + file.getName() + ":\n" + String.join("\n", msgs));
        }
    }

    // ── 4. SHACL validatie ───────────────────────────────────────────────────

    @Test
    void eachExamplePassesShaclValidation() {
        for (File file : exampleFiles) {
            Model dataModel = RdfUtils.parseTurtle(file);
            ValidationReport report = ShaclValidator.validate(dataModel, generatedShaclShapes);
            ShaclValidator.printReport(report);
            assertTrue(report.conforms(),
                    "SHACL-overtredingen in " + file.getName());
        }
    }

    // ── 5. Gecombineerde validatie van alle voorbeelden samen ─────────────────

    @Test
    void combinedExamplesPassAllValidations() {
        Model combined = ModelFactory.createDefaultModel();
        exampleFiles.forEach(f -> combined.add(RdfUtils.parseTurtle(f)));
        Model inferred = inferTriples(combined);

        // Vocabulary
        ValidationResult vocabResult = RdfUtils.checkVocabularyUsage(combined, completeOntology);
        List<String> vocabMsgs = JavaConverters.seqAsJavaList(vocabResult.messages());
        vocabMsgs.forEach(m -> LOG.warn("❌ [VOCAB] combined: {}", m));
        assertTrue(vocabResult.valid(),
                "Vocabulaire-overtredingen in gecombineerde voorbeelden:\n"
                        + String.join("\n", vocabMsgs));

        // Disjointness
        List<String> disjointViolations = checkDisjointness(inferred, completeOntology);
        disjointViolations.forEach(v -> LOG.warn("❌ [DISJOINT] combined: {}", v));
        assertTrue(disjointViolations.isEmpty(),
                "Disjointness-overtredingen in gecombineerde voorbeelden:\n"
                        + String.join("\n", disjointViolations));

        // OWL
        ValidationResult owlResult = RdfUtils.validateModel(inferred, owlReasonerWithSchema);
        List<String> owlMsgs = JavaConverters.seqAsJavaList(owlResult.messages());
        owlMsgs.forEach(m -> LOG.warn("❌ [OWL] combined: {}", m));
        assertTrue(owlResult.valid(),
                "OWL-overtredingen in gecombineerde voorbeelden:\n" + String.join("\n", owlMsgs));

        // SHACL
        ValidationReport shaclReport = ShaclValidator.validate(combined, generatedShaclShapes);
        ShaclValidator.printReport(shaclReport);
        assertTrue(shaclReport.conforms(), "SHACL-overtredingen in gecombineerde voorbeelden");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Model inferTriples(Model dataModel) {
        return RdfUtils.inferTriples(dataModel, completeOntology, ruleReasoner);
    }

    /**
     * Controleert of een resource tegelijk tot twee disjuncte klassen behoort.
     * Verwerkt zowel owl:AllDisjointClasses als pairwise owl:disjointWith.
     */
    private List<String> checkDisjointness(Model dataModel, Model ontology) {
        List<String> errors = new ArrayList<>();

        // Verzamel alle disjuncte klassenparen
        Set<List<String>> disjointPairs = new LinkedHashSet<>();

        // owl:AllDisjointClasses met owl:members
        ontology.listSubjectsWithProperty(RDF.type, OWL2.AllDisjointClasses)
                .forEachRemaining(allDisjoint -> {
                    Statement membersStmt = allDisjoint.getProperty(OWL_MEMBERS);
                    if (membersStmt == null) return;
                    List<Resource> members = membersStmt.getResource()
                            .as(RDFList.class).asJavaList().stream()
                            .filter(RDFNode::isResource)
                            .map(RDFNode::asResource)
                            .collect(Collectors.toList());
                    for (int i = 0; i < members.size(); i++)
                        for (int j = i + 1; j < members.size(); j++)
                            disjointPairs.add(Arrays.asList(
                                    members.get(i).getURI(),
                                    members.get(j).getURI()));
                });

        // Pairwise owl:disjointWith
        ontology.listStatements(null, OWL.disjointWith, (RDFNode) null)
                .forEachRemaining(stmt -> {
                    if (stmt.getSubject().isURIResource() && stmt.getObject().isURIResource()) {
                        String a = stmt.getSubject().getURI();
                        String b = stmt.getObject().asResource().getURI();
                        List<String> pair = Arrays.asList(a, b);
                        Collections.sort(pair);
                        disjointPairs.add(pair);
                    }
                });

        // Controleer voor elk paar of een resource beide types heeft
        for (List<String> pair : disjointPairs) {
            Resource c1 = ontology.createResource(pair.get(0));
            Resource c2 = ontology.createResource(pair.get(1));
            Set<Resource> typed1 = dataModel.listSubjectsWithProperty(RDF.type, c1).toSet();
            Set<Resource> typed2 = dataModel.listSubjectsWithProperty(RDF.type, c2).toSet();
            typed1.retainAll(typed2);
            typed1.forEach(r -> errors.add(
                    (r.isURIResource() ? r.getURI() : r.toString())
                            + " heeft tegelijk type " + c1.getLocalName()
                            + " en " + c2.getLocalName() + " (disjoint)"));
        }

        return errors;
    }

    private List<File> javaFiles(String classpathDir) throws URISyntaxException {
        File dir = new File(getClass().getClassLoader().getResource(classpathDir).toURI());
        return JavaConverters.seqAsJavaList(RdfUtils.listTurtleFiles(dir));
    }
}
