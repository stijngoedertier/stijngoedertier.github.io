package be.vlaanderen.omgeving.shaclvalidator;

import org.apache.jena.graph.Graph;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.vocabulary.RDF;
import org.topbraid.jenax.util.JenaUtil;
import org.topbraid.shacl.util.ModelPrinter;
import org.topbraid.shacl.validation.ValidationUtil;
import org.topbraid.shacl.vocabulary.SH;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Core engine that validates an RDF data graph against a set of SHACL shapes.
 *
 * <p>Usage:
 * <pre>
 *   ShaclValidator validator = new ShaclValidator();
 *   ValidationResult result  = validator.validate(shapesModel, dataModel);
 * </pre>
 */
public class ShaclValidator {

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Validate {@code dataModel} against the SHACL shapes in {@code shapesModel}.
     *
     * @param shapesModel Jena Model containing the SHACL shapes graph.
     * @param dataModel   Jena Model containing the RDF data to validate.
     * @return a {@link ValidationResult} describing whether the data conforms and
     *         listing all violations.
     */
    public ValidationResult validate(Model shapesModel, Model dataModel) {
        // TopBraid requires the data graph to be accessible via the OntManager;
        // we merge shapes + data so that shapes can reference data types etc.
        Model unionModel = JenaUtil.createMemoryModel();
        unionModel.add(dataModel);

        // Run the SHACL validation
        Resource report = ValidationUtil.validateModel(unionModel, shapesModel, true);

        boolean conforms = report.getProperty(SH.conforms).getBoolean();
        List<ValidationResult.Violation> violations = extractViolations(report);

        return new ValidationResult(conforms, violations);
    }

    /**
     * Validate RDF data read from {@code dataPath} against SHACL shapes read
     * from {@code shapesPath}.  The RDF serialisation format is auto-detected
     * from the file extension (Turtle, N-Triples, JSON-LD, RDF/XML, …).
     */
    public ValidationResult validateFiles(Path shapesPath, Path dataPath) throws Exception {
        Model shapesModel = loadModel(shapesPath);
        Model dataModel   = loadModel(dataPath);
        return validate(shapesModel, dataModel);
    }

    /**
     * Validate RDF data provided via InputStreams.
     *
     * @param shapesStream  stream of the shapes graph.
     * @param shapesLang    RDF language of the shapes stream (e.g. {@code Lang.TURTLE}).
     * @param dataStream    stream of the data graph.
     * @param dataLang      RDF language of the data stream.
     */
    public ValidationResult validateStreams(InputStream shapesStream, Lang shapesLang,
                                            InputStream dataStream,   Lang dataLang) {
        Model shapesModel = ModelFactory.createDefaultModel();
        RDFDataMgr.read(shapesModel, shapesStream, shapesLang);

        Model dataModel = ModelFactory.createDefaultModel();
        RDFDataMgr.read(dataModel, dataStream, dataLang);

        return validate(shapesModel, dataModel);
    }

    /**
     * Write the raw SHACL validation report (in Turtle) to the given stream.
     * Useful for machine-readable output or piping into other tools.
     */
    public void writeReport(Model shapesModel, Model dataModel, OutputStream out) {
        Model unionModel = JenaUtil.createMemoryModel();
        unionModel.add(dataModel);
        Resource report = ValidationUtil.validateModel(unionModel, shapesModel, true);
        RDFDataMgr.write(out, report.getModel(), Lang.TURTLE);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Model loadModel(Path path) throws Exception {
        Model model = ModelFactory.createDefaultModel();
        Lang lang = detectLang(path);
        try (InputStream is = Files.newInputStream(path)) {
            RDFDataMgr.read(model, is, lang);
        }
        return model;
    }

    /**
     * Detect the RDF serialisation language from the file extension.
     * Falls back to Turtle when the extension is not recognised.
     */
    static Lang detectLang(Path path) {
        String filename = path.getFileName().toString().toLowerCase();
        if (filename.endsWith(".ttl"))    return Lang.TURTLE;
        if (filename.endsWith(".nt"))     return Lang.NTRIPLES;
        if (filename.endsWith(".nq"))     return Lang.NQUADS;
        if (filename.endsWith(".trig"))   return Lang.TRIG;
        if (filename.endsWith(".jsonld")) return Lang.JSONLD;
        if (filename.endsWith(".rdf") || filename.endsWith(".xml")) return Lang.RDFXML;
        if (filename.endsWith(".n3"))     return Lang.N3;
        // default
        return Lang.TURTLE;
    }

    /**
     * Walk the sh:ValidationResult resources in the report and build our
     * domain objects.
     */
    private List<ValidationResult.Violation> extractViolations(Resource report) {
        List<ValidationResult.Violation> list = new ArrayList<>();

        StmtIterator results = report.getModel()
                .listStatements(null, RDF.type, SH.ValidationResult);

        while (results.hasNext()) {
            Resource vr = results.next().getSubject();

            String severity   = getStringOrNull(vr, SH.resultSeverity);
            String focusNode  = getStringOrNull(vr, SH.focusNode);
            String path       = getStringOrNull(vr, SH.resultPath);
            String constraint = getStringOrNull(vr, SH.sourceConstraintComponent);
            String shape      = getStringOrNull(vr, SH.sourceShape);
            String message    = getStringOrNull(vr, SH.resultMessage);
            String value      = getStringOrNull(vr, SH.value);

            list.add(new ValidationResult.Violation(
                    severity, focusNode, path, constraint, shape, message, value));
        }

        return list;
    }

    private String getStringOrNull(Resource subject, org.apache.jena.rdf.model.Property property) {
        var stmt = subject.getProperty(property);
        if (stmt == null) return null;
        RDFNode node = stmt.getObject();
        if (node.isLiteral())  return node.asLiteral().getString();
        if (node.isResource()) return node.asResource().getURI();
        return node.toString();
    }
}