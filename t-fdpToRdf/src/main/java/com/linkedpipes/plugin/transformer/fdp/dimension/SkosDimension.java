package com.linkedpipes.plugin.transformer.fdp.dimension;

import com.linkedpipes.etl.executor.api.v1.LpException;
import com.linkedpipes.etl.executor.api.v1.service.ExceptionFactory;
import com.linkedpipes.plugin.transformer.fdp.FdpAttribute;
import com.linkedpipes.plugin.transformer.fdp.FdpToRdfVocabulary;
import com.linkedpipes.plugin.transformer.fdp.Mapper;
import com.linkedpipes.plugin.transformer.fdp.dimension.FdpDimension;

import java.io.IOException;
import java.util.HashMap;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;

public class SkosDimension extends FdpDimension {
    public static final String dimensionQuery = "PREFIX qb: <http://purl.org/linked-data/cube#>\n" +
                    "PREFIX fdprdf: <http://data.openbudgets.eu/fdptordf#>\n" +
                    "PREFIX skos: <http://www.w3.org/2004/02/skos/core#>\n" +
                    "\n" +
                    "SELECT DISTINCT ?dimensionProp ?dimensionName ?packageName ?dataset\n" +
                    "WHERE {\n" +
                    " ?component fdprdf:attributeCount ?attrCount .\n" +
                    "  FILTER(?attrCount > 1)\n" +
                    "  \n" +
                    "  ?dsd a qb:DataStructureDefinition;\n" +
                    "         qb:component ?component .\n" +
                    "  ?component qb:dimension ?dimensionProp;\n" +
                    "             fdprdf:attribute ?attribute ;\n" +
                    "             fdprdf:valueType fdprdf:skos .\n" +
                    "             \n" +
                    "  ?dimensionProp fdprdf:name ?dimensionName .\n" +
                    "  \n" +
                    "  ?attribute fdprdf:source ?sourceProperty ;\n" +
                    "             fdprdf:valueProperty ?attributeValueProperty .\n" +
                    "                        \n" +
                    "  ?dataset a qb:DataSet;  \n" +
                    "      \t   qb:structure ?dsd ;\n" +
                    "          fdprdf:datasetShortName ?packageName .\n" +
                    "    \n" +
                    "  {\n" +
                    "    SELECT ?component (COUNT(?attribute) AS ?nonHierarchCount)\n" +
                    "    WHERE {\n" +
                    "        ?component fdprdf:attribute ?attribute .\n" +
                    "        FILTER NOT EXISTS {?attribute fdprdf:isHierarchical true .}\n" +
                    "    } GROUPBY ?component\n" +
                    "  }             \n" +
                    "  \n" +
                    "  FILTER (?nonHierarchCount = ?attrCount)              \n" +
                    "}";

    public static final String attributeQuery =
            "PREFIX qb: <http://purl.org/linked-data/cube#>\n" +
            "PREFIX fdprdf: <http://data.openbudgets.eu/fdptordf#>\n" +
            "\n" +
            "SELECT *\n" +
            "WHERE {\n" +
            "  ?component qb:dimension _dimensionProp_;\n" +
            "             fdprdf:attribute ?attribute ;\n" +
            "             fdprdf:valueType fdprdf:skos .             \n" +
            "  \n" +
            "  ?attribute fdprdf:sourceColumn ?sourceColumn ;\n" +
            "\t\t\t fdprdf:sourceFile ?sourceFile;\n" +
            "\t\t\t fdprdf:iskey ?iskey;\n" +
            "             fdprdf:valueProperty ?attributeValueProperty;\n" +
                    "             fdprdf:name ?attributeName .      \n" +
            "FILTER NOT EXISTS {?attribute fdprdf:isHierarchical true .}  \n" +
            "}";

    public SkosDimension() {}

    public String getAttributeQueryTemplate() {
        return this.attributeQuery;
    }

    public void processRow(IRI observation, HashMap<String, String> row, ExceptionFactory exceptionFactory) throws LpException, IOException {
        Resource dimensionVal = createValueIri(row);
        boolean weHaveLabel = false;
        String attrVal = null;
        for(FdpAttribute attr : attributes) {
            attrVal = row.get(attr.getColumn());
            if(attrVal!=null && attr.getLabelColumn() != null) {
                String attrLabel = row.get(attr.getLabelColumn());
                if(attrLabel!=null) {
                    output.submit(dimensionVal, Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.SKOS_PREFLABEL), Mapper.VALUE_FACTORY.createLiteral(attrLabel));
                    weHaveLabel = true;
                }
            }
            if(attrVal != null) {
                if(weHaveLabel) {
                    output.submit(dimensionVal, Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.SKOS_NOTATION), Mapper.VALUE_FACTORY.createLiteral(attrVal));
                }
                else output.submit(dimensionVal, Mapper.VALUE_FACTORY.createIRI(attr.getPartialPropertyIri().stringValue()), Mapper.VALUE_FACTORY.createLiteral(attrVal));
            }
        }
        if(attrVal != null) {
            output.submit(observation, this.valueProperty, dimensionVal);
            output.submit(dimensionVal, Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.A), Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.SKOS_CONCEPT));
            if (weHaveLabel == false)
                output.submit(dimensionVal, Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.SKOS_PREFLABEL), Mapper.VALUE_FACTORY.createLiteral(mergedPrimaryKey(row)));
            output.submit(dimensionVal, Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.SKOS_INSCHEME), getCodelistIRI());
            output.submit(getCodelistIRI(), Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.A), Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.SKOS_CONCEPTSCHEME));
            output.submit(getCodelistIRI(), Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.SKOS_HASTOPCONCEPT), dimensionVal);
            output.submit(getCodelistIRI(), Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.RDFS_LABEL), Mapper.VALUE_FACTORY.createLiteral(name));
            output.submit(this.valueProperty, Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.QB_CODELIST), getCodelistIRI());
        }
    }

}