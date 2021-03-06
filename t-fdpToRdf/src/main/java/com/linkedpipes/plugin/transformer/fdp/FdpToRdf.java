package com.linkedpipes.plugin.transformer.fdp;


import com.linkedpipes.etl.dataunit.core.files.FilesDataUnit;
import com.linkedpipes.etl.dataunit.core.files.FilesDataUnit.Entry;
import com.linkedpipes.etl.dataunit.core.files.WritableFilesDataUnit;
import com.linkedpipes.etl.dataunit.core.rdf.SingleGraphDataUnit;
import com.linkedpipes.etl.executor.api.v1.LpException;
import com.linkedpipes.etl.executor.api.v1.component.Component;
import com.linkedpipes.etl.executor.api.v1.component.SequentialExecution;
import com.linkedpipes.etl.executor.api.v1.service.ExceptionFactory;
import com.linkedpipes.plugin.transformer.fdp.dimension.*;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.Binding;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.impl.SimpleDataset;

import org.supercsv.prefs.CsvPreference;

public final class FdpToRdf implements Component, SequentialExecution {

    private static final String VERSION = "FdpToRdf-v0.8.0_1:";

    private static final String FILE_ENCODE = "UTF-8";

    private static final ValueFactory VALUE_FACTORY
    = SimpleValueFactory.getInstance();

    private static final Logger LOG
            = LoggerFactory.getLogger(FdpToRdf.class);

    @Component.InputPort(iri = "InputRdf")
    public SingleGraphDataUnit inputRdf;

    @Component.InputPort(iri = "InputFiles")
    public FilesDataUnit inputFilesDataUnit;

    /*@Component.InputPort(id = "OutputRdf")*/
    //public WritableSingleGraphDataUnit outputRdf;

    @Component.OutputPort(iri = "OutputFile")
    public WritableFilesDataUnit outputFiles;

    @Component.Configuration
    public FdpToRdfConfiguration configuration;

    @Component.Inject
    public ExceptionFactory exceptionFactory;

    private PlainTextTripleWriter output;

    private OutputStreamWriter outWriter;

    private List<FdpDimension> dimensions = new ArrayList<FdpDimension>();
    private List<HierarchicalDimension> hierarchicalDimensions = new ArrayList<HierarchicalDimension>();
    private List<FdpMeasure> measures = new ArrayList<FdpMeasure>();

    private List<BindingSet> currentResult = null;

    public void storeCurrentResult(List<BindingSet> result) {
    	currentResult = result;
    }

    private String datasetIRI;
    private String packageName;
    private boolean outputCurrencyAsDimension = false;

    private List<BindingSet> execQuery(String queryText) throws LpException {
    	try{
    	inputRdf.execute((connection) -> {

    		//String queryText = "PREFIX fdprdf: <http://data.openbudgets.eu/fdptordf#> SELECT ?attribute ?valueProp ?parentDimension WHERE { ?attribute fdprdf:valueProperty ?valueProp; fdprdf:parentDimension ?parentDimension . }";

            final TupleQuery query = connection.prepareTupleQuery(
            		QueryLanguage.SPARQL, queryText);
            final SimpleDataset dataset = new SimpleDataset();
            final IRI inputGraph = inputRdf.getReadGraph();
            dataset.addDefaultGraph(inputGraph);
            // We need to add this else we can not use
            // GRAPH ?g in query.
            dataset.addNamedGraph(inputGraph);
            query.setDataset(dataset);
            final TupleQueryResult result = query.evaluate();
            final List<BindingSet> resultBindings = new ArrayList<BindingSet>();
            while (result.hasNext()) {
                resultBindings.add(result.next());
            }
            storeCurrentResult(resultBindings);
    	});
    	}
    	catch(Exception ex) {
    		 throw exceptionFactory.failure(VERSION+"Can't extract metadata, the failure query was: \r\n {}", queryText);
    	}
    	return currentResult;
    }

    private void extractDataset() throws LpException {
        execQuery(FdpMeasure.query);
        if(currentResult.size() == 0) throw exceptionFactory.failure(VERSION+"Dataset IRI not found in metadata.");
        Binding datasetBinding = currentResult.get(0).getBinding("dataset");
        Binding packageNameBinding = currentResult.get(0).getBinding("packageName");
        if(datasetBinding == null) throw exceptionFactory.failure(VERSION+"Dataset IRI not found in metadata.");
        Binding hasCurrencyDimension = currentResult.get(0).getBinding("hasCurrencyDimension");
        if(hasCurrencyDimension!=null) outputCurrencyAsDimension = true;
        datasetIRI = datasetBinding.getValue().stringValue();
        packageName = packageNameBinding.getValue().stringValue();
    }

    private CsvPreference extractHeader(String resourcePath) throws LpException {
        execQuery(HeaderParser.resourceQuery(resourcePath));
        if(currentResult.size() ==0) throw exceptionFactory.failure(VERSION+"Cannot read resource schema.");
        Binding delimiterBinding = currentResult.get(0).getBinding("delimiter");
        Binding quoteCharBinding = currentResult.get(0).getBinding("quoteChar");
        HeaderParser headerParser = new HeaderParser();
        if(delimiterBinding != null) headerParser.setDelimiter(delimiterBinding.getValue().stringValue());
        if(quoteCharBinding != null) headerParser.setQuoteChar(quoteCharBinding.getValue().stringValue());
        return headerParser.getCsvPreference();
    }

    private void extractDimensions() throws LpException {

       execQuery(MultiAttributeDimension.dimensionQuery);
       for(BindingSet bs : currentResult) {
            MultiAttributeDimension dim = new MultiAttributeDimension();
            initDimension(dim, bs);
            dimensions.add(dim);
            //output.submit(valueProp, VALUE_FACTORY.createIRI("http://hasParent"), parentDimension);
        }

        execQuery(SkosDimension.dimensionQuery);
        for(BindingSet bs : currentResult) {
            SkosDimension dim = new SkosDimension();
            initDimension(dim, bs);
            dimensions.add(dim);
        }

        execQuery(HierarchicalDimension.dimensionQuery);
        for(BindingSet bs : currentResult) {
            HierarchicalDimension dim = new HierarchicalDimension();
            initDimension(dim, bs);
            hierarchicalDimensions.add(dim);
        }

        execQuery(SingleAttributeObjectDimension.dimensionQuery);
        for(BindingSet bs : currentResult) {
            SingleAttributeObjectDimension dim = new SingleAttributeObjectDimension();
            initDimension(dim, bs);
            dimensions.add(dim);
        }

        execQuery(SingleAttributeLiteralDimension.dimensionQuery);
        for(BindingSet bs : currentResult) {
            SingleAttributeLiteralDimension dim = new SingleAttributeLiteralDimension();
            initDimension(dim, bs);
            dimensions.add(dim);
        }

        execQuery(DateDimension.dimensionQuery);
        for(BindingSet bs : currentResult) {
            DateDimension dim = new DateDimension();
            initDimension(dim, bs);
            dimensions.add(dim);
        }

        execQuery(SingleAttributeSkosDimension.dimensionQuery);
        for(BindingSet bs : currentResult) {
            SingleAttributeSkosDimension dim = new SingleAttributeSkosDimension();
            initDimension(dim, bs);
            dimensions.add(dim);
        }
    }

    private void extractMeasures() throws LpException {
        execQuery(FdpMeasure.query);
        for(BindingSet bs : currentResult) {
            FdpMeasure newMeasure = new FdpMeasure(output,
                    ((Literal)bs.getBinding("measureFactor").getValue()).doubleValue(),
                    bs.getBinding("measureProperty").getValue().stringValue(),
                    bs.getBinding("measureName").getValue().stringValue(),
                    bs.getBinding("sourceColumn").getValue().stringValue(),
                    bs.getBinding("sourceFile").getValue().stringValue());
            Binding decimalCharBinding = bs.getBinding("decimalChar");
            Binding groupCharBinding = bs.getBinding("groupChar");
            Binding currencyBinding = bs.getBinding("currency");
            Binding operationCharBinding = bs.getBinding("operationCharacter");
            Binding budgetPhaseBinding = bs.getBinding("budgetPhase");
            if(decimalCharBinding != null) newMeasure.setDecimalSep(decimalCharBinding.getValue().stringValue().charAt(0));
            if(groupCharBinding != null) newMeasure.setGroupSep(groupCharBinding.getValue().stringValue().charAt(0));
            if(currencyBinding != null) newMeasure.setCurrency((IRI) currencyBinding.getValue());
            if(operationCharBinding != null) newMeasure.setOperationChar((IRI) operationCharBinding.getValue());
            if(budgetPhaseBinding != null) newMeasure.setBudgetPhase((IRI) budgetPhaseBinding.getValue());
            newMeasure.setOutputCurrencyDimension(outputCurrencyAsDimension);
            measures.add(newMeasure);
        }
    }

    private void extractAttributes() throws LpException {
    	for(FdpDimension dim : dimensions) {
    		List<FdpAttribute> attributes = new ArrayList<FdpAttribute>();
    		execQuery(dim.getAttributeQuery());
    		for(BindingSet bs : currentResult) {
                FdpAttribute attr = new FdpAttribute(
                		bs.getBinding("sourceColumn").getValue().stringValue(),
                		bs.getBinding("sourceFile").getValue().stringValue(),
                		((Literal) bs.getBinding("iskey").getValue()).booleanValue(),
                		(Resource) bs.getBinding("attributeValueProperty").getValue());
                if(bs.getBinding("attributeName") != null) {
                    attr.setName(bs.getBinding("attributeName").getValue().stringValue());
                }
                attributes.add(attr);
    		}
    		dim.setAttributes(attributes);

            execQuery(dim.getLabelsQuery());
            for(BindingSet bs : currentResult) {
                dim.addLabel(bs.getBinding("labelForName").getValue().stringValue(), bs.getBinding("sourceColumn").getValue().stringValue());
            }
    	}
        for(HierarchicalDimension dim : hierarchicalDimensions) {
            List<FdpAttribute> attributes = new ArrayList<FdpAttribute>();
            execQuery(dim.getAttributeQuery());
            for(BindingSet bs : currentResult) {
                FdpHierarchicalAttribute attr = new FdpHierarchicalAttribute(
                        bs.getBinding("sourceColumn").getValue().stringValue(),
                        bs.getBinding("sourceFile").getValue().stringValue(),
                        ((Literal) bs.getBinding("iskey").getValue()).booleanValue(),
                        (Resource) bs.getBinding("attributeValueProperty").getValue(),
                        bs.getBinding("attributeName").getValue().stringValue());
                if(bs.getBinding("parentName")!=null) attr.setParent(bs.getBinding("parentName").getValue().stringValue());
                attributes.add(attr);
            }
            dim.setAttributes(attributes);

            execQuery(dim.getLabelsQuery());
            for(BindingSet bs : currentResult) {
                dim.addLabel(bs.getBinding("labelForName").getValue().stringValue(), bs.getBinding("sourceColumn").getValue().stringValue());
            }
        }

    }

    private void initDimension(FdpDimension dimension, BindingSet bs) {
        IRI valueProp = (IRI) bs.getBinding("dimensionProp").getValue();
        String dimName = (String) bs.getBinding("dimensionName").getValue().stringValue();
        String datasetIri = (String) bs.getBinding("dataset").getValue().stringValue();
        String datasetName = (String) bs.getBinding("packageName").getValue().stringValue();
        dimension.init(output, valueProp, dimName, datasetIri, datasetName);
        if(bs.getBinding("rdfType")!=null) dimension.setValueType((IRI) bs.getBinding("rdfType").getValue());
    }

    private FileOutputStream outputStream;
    @Override
    public void execute() throws LpException {

        // changing to plaintext output
        //output = new BufferedOutput(outputRdf);
        extractDataset();

        final File outputFile = outputFiles.createFile(packageName+".nt");
        try {
            //FileOutputStream outStream = new FileOutputStream(outputFile);
            //OutputStreamWriter outWriter = new OutputStreamWriter(outStream, Charset.forName(FILE_ENCODE));
            outputStream = new FileOutputStream(outputFile);
            outWriter = new OutputStreamWriter(outputStream, Charset.forName("UTF-8").newEncoder());//new FileWriter(outputFile, );
            output = new PlainTextTripleWriter(outWriter);
        }
        catch (IOException ex){
            throw exceptionFactory.failure(VERSION+"Can't initialize file for data output.", ex);
        }

        final Parser parser = new Parser(exceptionFactory);

        extractDimensions();
        extractAttributes();
        extractMeasures();
        List<FdpDimension> allDimensions = new ArrayList<FdpDimension>();
        allDimensions.addAll(dimensions);
        allDimensions.addAll(hierarchicalDimensions);
        final Mapper mapper = new Mapper(output, exceptionFactory, allDimensions, measures, datasetIRI);

        //output.onFileStart();

        if(inputFilesDataUnit.size() > 2) throw exceptionFactory.failure(VERSION+"Only one CSV file is supported at the moment.");
        for (Entry entry : inputFilesDataUnit) {

            LOG.info("Processing file: {}", entry.toFile());
            try {
                if(!entry.getFileName().endsWith(".nt")) {
                    parser.parse(entry, mapper, extractHeader(entry.getFileName()));
                    output.onFileEnd();
                }
                else if(entry.getFileName().endsWith(".nt")) {
                    FileInputStream fileInputStream = new FileInputStream(entry.toFile());
                    IOUtils.copy(fileInputStream, outputStream);
                    fileInputStream.close();
                    outputStream.flush();
                }
            } catch (Exception ex) {
                throw exceptionFactory.failure(VERSION+"Can't process file: {}",
                        entry.getFileName(), ex);
            }
        }

        try {
            outputStream.close();
        } catch (IOException e) {
            throw exceptionFactory.failure(VERSION+"Can't close output file.", e);
        }
    }

}
