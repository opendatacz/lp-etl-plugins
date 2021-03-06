package com.linkedpipes.plugin.transformer.fdp;

import com.linkedpipes.etl.executor.api.v1.LpException;
import com.linkedpipes.etl.executor.api.v1.service.ExceptionFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import org.eclipse.rdf4j.model.IRI;

/**
 * Created by admin on 21.8.2016.
 */
public class FdpMeasure {
    private double factor;
    private String measureProperty;
    private String column;
    private String name;
    private String file;
    private PlainTextTripleWriter output;
    private FdpAttribute attr;
    private boolean outputCurrencyDimension = false;
    private boolean multiMeasure = false;

    private IRI budgetPhase = null, currency = null, operationCharacter = null;

    public static final String query =
            "PREFIX qb: <http://purl.org/linked-data/cube#>\n" +
            "PREFIX fdprdf: <http://data.openbudgets.eu/fdptordf#>\n" +
                    "PREFIX fdp: <http://schemas.frictionlessdata.io/fiscal-data-package#>\n" +
                    "PREFIX obeu-attribute:   <http://data.openbudgets.eu/ontology/dsd/attribute/>\n" +
                    "PREFIX obeu-dimension:   <http://data.openbudgets.eu/ontology/dsd/dimension/>\n" +
            "\n" +
            "SELECT *\n" +
            "WHERE {\n" +
            "  ?dsd a qb:DataStructureDefinition;\n" +
            "       fdprdf:component ?component ." +
            "       ?component fdprdf:measure ?measureProperty;\n" +
            "                      fdprdf:source ?measureSource; \n" +
            "                      fdprdf:factor ?measureFactor;\n" +
            "  \t\t\t\t\t  fdprdf:sourceColumn ?sourceColumn;\n" +
            "  \t\t\t\t\t  fdprdf:sourceFile ?sourceFile .\n" +
            "                        \n" +
            "  ?dataset a qb:DataSet;\n" +
            "      fdprdf:datasetShortName ?packageName ;\n" +
            "      \t   qb:structure ?dsd .\n" +
                    "\n" +
                    "?measureProperty fdprdf:name ?measureName ." +
            "   OPTIONAL { ?component fdprdf:decimalChar ?decimalChar . }\n" +
                    " OPTIONAL { ?component fdprdf:groupChar ?groupChar . }\n" +
                    " OPTIONAL { ?component fdprdf:fieldType ?fieldType . }\n" +
                    " OPTIONAL { ?measureProperty fdprdf:operationCharacter ?operationCharacter . }\n" +
                    " OPTIONAL { ?measureProperty fdprdf:budgetPhase ?budgetPhase . }\n" +
                    " OPTIONAL { ?measureProperty fdprdf:currency ?currency . }\n" +
                    " OPTIONAL { ?dsd qb:component/qb:dimension obeu-dimension:currency . " +
                    "            BIND(true as ?hasCurrencyDimension) }" +
            "}";

    public FdpMeasure(PlainTextTripleWriter output, double factor, String measureProperty, String measureName, String column, String file) {
        this.factor = factor;
        this.measureProperty = FdpToRdfVocabulary.OBEU_AMOUNT;//measureProperty;
        this.name = measureName;
        this.column = column;
        this.file = file;
        this.output = output;

        attr = new FdpAttribute(column, file, false, Mapper.VALUE_FACTORY.createIRI(measureProperty));
        this.attr.setFormat(FdpAttribute.Format.NUMBER);
    }

    public void setDecimalSep(char sep) {
        this.attr.setDecimalSep(sep);
    }

    public void setGroupSep(char sep) {
        this.attr.setGroupSep(sep);
    }

    public void setCurrency(IRI currency) {this.currency = currency;}
    public void setOperationChar(IRI operation) {this.operationCharacter = operation;}
    public void setBudgetPhase(IRI phase) {this.budgetPhase = phase;}
    public void setOutputCurrencyDimension(boolean shouldOutput) {outputCurrencyDimension = shouldOutput;}

    public String getName() { return this.name;}

    public void processRow(IRI observationIri, HashMap<String, String> row, ExceptionFactory exceptionFactory) throws LpException, IOException {
        String measureValString = row.get(column);
        if(measureValString!=null) {
            Double numericVal = (Double) attr.parseValue(measureValString);
            if (numericVal != null) {
                BigDecimal measureVal = new BigDecimal(numericVal * this.factor);
                BigDecimal roundedVal = measureVal.setScale(2, RoundingMode.HALF_UP);
                output.submit(observationIri,
                        Mapper.VALUE_FACTORY.createIRI(measureProperty),
                        Mapper.VALUE_FACTORY.createLiteral(roundedVal));
            } else {
                output.submit(observationIri,
                        Mapper.VALUE_FACTORY.createIRI(measureProperty),
                        Mapper.VALUE_FACTORY.createLiteral(measureValString));
            }

            if (this.budgetPhase != null) output.submit(observationIri,
                    Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.OBEU_DIMENSION_BUDGETPHASE),
                    this.budgetPhase);
            if (this.operationCharacter != null) output.submit(observationIri,
                    Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.OBEU_DIMENSION_OPERATIONCHARACTER),
                    this.operationCharacter);
            if (this.currency != null) {
                output.submit(observationIri,
                        Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.OBEU_ATTRIBUTE_CURRENCY),
                        this.currency);
                if(outputCurrencyDimension) output.submit(observationIri,
                        Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.OBEU_DIMENSION_CURRENCY),
                        this.currency);
            }
            if(multiMeasure) output.submit(observationIri,
                    Mapper.VALUE_FACTORY.createIRI(FdpToRdfVocabulary.QB_MEASURE_TYPE),
                    Mapper.VALUE_FACTORY.createIRI(measureProperty));
        }
    }
}
