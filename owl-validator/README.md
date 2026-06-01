# OWL Ontology Validator

Validates OWL 2 ontologies and RDF instance data using the
**OWL API 5** + **HermiT** reasoner stack.

## What it validates

- **Consistency** Any logical contradiction in the merged axiom set
- **Class satisfiability** Classes that can never have instances (e.g. caused by conflicting `owl:disjointWith` + `rdfs:subClassOf`)
- **Individual typing** Named individuals without an explicit `rdf:type` assertion
- **Inferred axioms** Reports how many new facts HermiT derives (informational)

