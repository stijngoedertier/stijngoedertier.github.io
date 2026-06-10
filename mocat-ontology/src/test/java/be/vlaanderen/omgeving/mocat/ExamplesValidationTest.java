package be.vlaanderen.omgeving.mocat;

import be.vlaanderen.omgeving.rdfvalidator.RdfUtils;
import be.vlaanderen.omgeving.rdfvalidator.ValidationResult;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import scala.collection.JavaConverters;

import java.io.File;
import java.net.URISyntaxException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ExamplesValidationTest {

    private Model ontologyModel;
    private List<File> exampleFiles;

    @BeforeAll
    void setUp() throws URISyntaxException {
        ontologyModel = ModelFactory.createDefaultModel();
        for (File f : javaFiles("ontologies")) {
            ontologyModel.add(RdfUtils.parseTurtle(f));
        }
        exampleFiles = javaFiles("examples");
    }

    private List<File> javaFiles(String classpathDir) throws URISyntaxException {
        File dir = new File(getClass().getClassLoader().getResource(classpathDir).toURI());
        return JavaConverters.seqAsJavaList(RdfUtils.listTurtleFiles(dir));
    }

    private File classpathFile(String path) throws URISyntaxException {
        return new File(getClass().getClassLoader().getResource(path).toURI());
    }

    @Test
    void examplesDirectoryContainsAtLeastOneFile() {
        assertFalse(exampleFiles.isEmpty(),
                "De examples map moet minstens één turtle bestand bevatten");
    }

    @Test
    void eachExampleFileParsesAsValidTurtle() {
        for (File file : exampleFiles) {
            Model model = RdfUtils.parseTurtle(file);
            assertTrue(model.size() > 0,
                    file.getName() + " bevat geen triples");
        }
    }

    @Test
    void eachExampleFileUsesOnlyKnownVocabulary() {
        for (File file : exampleFiles) {
            Model dataModel = RdfUtils.parseTurtle(file);
            ValidationResult result = RdfUtils.checkVocabularyUsage(dataModel, ontologyModel);
            List<String> messages = JavaConverters.seqAsJavaList(result.messages());
            assertTrue(result.valid(),
                    "Vocabulaire-overtredingen in " + file.getName() + ":\n" + String.join("\n", messages));
        }
    }

    @Test
    void simulationModelContainsProvPlan() throws URISyntaxException {
        Model model = RdfUtils.parseTurtle(classpathFile("examples/simulation-model.ttl"));
        assertTrue(
                model.listSubjectsWithProperty(RDF.type,
                        model.createResource("http://www.w3.org/ns/prov#Plan")).hasNext(),
                "simulation-model.ttl moet een prov:Plan resource bevatten");
    }

    @Test
    void dataSpecificationContainsShaclNodeShape() throws URISyntaxException {
        Model model = RdfUtils.parseTurtle(classpathFile("examples/data-specification.ttl"));
        assertTrue(
                model.listSubjectsWithProperty(RDF.type,
                        model.createResource("http://www.w3.org/ns/shacl#NodeShape")).hasNext(),
                "data-specification.ttl moet minstens één sh:NodeShape bevatten");
    }

    @Test
    void simulationRunContainsProvActivity() throws URISyntaxException {
        Model model = RdfUtils.parseTurtle(classpathFile("examples/simulation-run.ttl"));
        assertTrue(
                model.listSubjectsWithProperty(RDF.type,
                        model.createResource("http://www.w3.org/ns/prov#Activity")).hasNext(),
                "simulation-run.ttl moet een prov:Activity resource bevatten");
    }

    @Test
    void datasetContainsDcatDataset() throws URISyntaxException {
        Model model = RdfUtils.parseTurtle(classpathFile("examples/dataset.ttl"));
        assertTrue(
                model.listSubjectsWithProperty(RDF.type,
                        model.createResource("http://www.w3.org/ns/dcat#Dataset")).hasNext(),
                "dataset.ttl moet een dcat:Dataset resource bevatten");
    }
}
