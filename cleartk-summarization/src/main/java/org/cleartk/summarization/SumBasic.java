/** 
 * 
 * Copyright (c) 2007-2012, Regents of the University of Colorado 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer. 
 * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution. 
 * Neither the name of the University of Colorado at Boulder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission. 
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE. 
 */
package org.cleartk.summarization;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;

import org.apache.commons.io.FileUtils;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.impl.XmiCasDeserializer;
import org.apache.uima.cas.impl.XmiCasSerializer;
import org.apache.uima.collection.CollectionReader;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.XMLSerializer;
import org.cleartk.classifier.CleartkAnnotator;
import org.cleartk.classifier.jar.DefaultDataWriterFactory;
import org.cleartk.classifier.jar.DirectoryDataWriterFactory;
import org.cleartk.classifier.jar.GenericJarClassifierFactory;
import org.cleartk.classifier.jar.JarClassifierBuilder;
import org.cleartk.summarization.SumBasicAnnotator.TokenField;
import org.cleartk.summarization.SumBasicModel.CompositionFunctionType;
import org.cleartk.summarization.classifier.SumBasicDataWriter;
import org.cleartk.syntax.opennlp.PosTaggerAnnotator;
import org.cleartk.syntax.opennlp.SentenceAnnotator;
import org.cleartk.token.stem.snowball.DefaultSnowballStemmer;
import org.cleartk.token.tokenizer.TokenAnnotator;
import org.cleartk.util.Options_ImplBase;
import org.cleartk.util.ViewURIUtil;
import org.cleartk.util.ae.UriToDocumentTextAnnotator;
import org.cleartk.util.cr.UriCollectionReader;
import org.kohsuke.args4j.Option;
import org.uimafit.component.JCasAnnotator_ImplBase;
import org.uimafit.descriptor.ConfigurationParameter;
import org.uimafit.factory.AggregateBuilder;
import org.uimafit.factory.AnalysisEngineFactory;
import org.uimafit.factory.ConfigurationParameterFactory;
import org.uimafit.pipeline.SimplePipeline;
import org.xml.sax.SAXException;

import com.google.common.io.Files;

public class SumBasic extends Summarize_ImplBase<File> {

  private static final int DEFAULT_MAX_NUM_SENTENCES = 10;

  private static final double DEFAULT_SEEN_WORDS_PROB = 0.0001;

  private static final CompositionFunctionType DEFAULT_CF_TYPE = CompositionFunctionType.AVERAGE;

  private static final TokenField DEFAULT_TOKEN_FIELD = TokenField.COVERED_TEXT;

  // public static final String MODEL_NAME = "model.sumbasic";

  private File documentsDirectory;

  private File modelDirectory;

  private File xmiDirectory;

  private Collection<File> items;

  private File stopwordsFile;

  private Double seenWordsProbability = DEFAULT_SEEN_WORDS_PROB;

  private int numSentences = DEFAULT_MAX_NUM_SENTENCES;

  private CompositionFunctionType cfType = DEFAULT_CF_TYPE;

  private TokenField tokenField = DEFAULT_TOKEN_FIELD;

  private boolean outputSentences = false;

  private boolean outputScores = false;

  private File sentencesOutFile;

  public SumBasic(
      File documentsDirectory,
      File modelDirectory,
      File xmiDirectory,
      File stopwordsFile,
      SumBasicAnnotator.TokenField tokenField) {
    this.documentsDirectory = documentsDirectory;
    this.modelDirectory = modelDirectory;
    this.xmiDirectory = xmiDirectory;

    this.stopwordsFile = stopwordsFile;
    this.tokenField = tokenField;

    this.items = FileUtils.listFiles(
        this.documentsDirectory,
        new UriCollectionReader.RejectSystemFiles(),
        new UriCollectionReader.RejectSystemDirectories());
  }

  @Override
  protected CollectionReader getCollectionReader(Collection<File> files) throws Exception {
    return UriCollectionReader.getCollectionReaderFromFiles(files);
  }

  @Override
  protected void train() throws Exception {
    AggregateBuilder builder = this.buildTrainingAggregate();

    // Run preprocessing and tfidf counts analyzer
    SimplePipeline.runPipeline(
        this.getCollectionReader(items),
        builder.createAggregateDescription());

    // For more details on the training arguments refer to SumBasicModel
    String[] trainingArgs = {
        "--max-num-sentences",
        Integer.toString(this.numSentences),
        "--seen-words-prob",
        Double.toString(this.seenWordsProbability),
        "--composition-function",
        this.cfType.toString() };

    JarClassifierBuilder.trainAndPackage(this.modelDirectory, trainingArgs);
  }

  @Override
  public void extract() throws Exception {
    Collection<File> xmis = FileUtils.listFiles(
        this.xmiDirectory,
        new UriCollectionReader.RejectSystemFiles(),
        new UriCollectionReader.RejectSystemDirectories());

    // This simply runs the model and tags the extracted sentences in the CAS and writes the output
    // to a file if specified.
    AggregateBuilder builder = buildExtractAggregate();

    // Run preprocessing and tfidf counts analyzer
    SimplePipeline.runPipeline(this.getCollectionReader(xmis), builder.createAggregateDescription());

  }

  public AggregateBuilder buildTrainingAggregate() throws ResourceInitializationException {

    AggregateBuilder builder = new AggregateBuilder();

    builder.add(UriToDocumentTextAnnotator.getDescription());

    // NLP pre-processing components
    builder.add(SentenceAnnotator.getDescription());
    builder.add(TokenAnnotator.getDescription());
    builder.add(PosTaggerAnnotator.getDescription());
    builder.add(DefaultSnowballStemmer.getDescription("English"));

    // This will extract the features for summarization
    builder.add(AnalysisEngineFactory.createPrimitiveDescription(
        SumBasicAnnotator.class,
        DefaultDataWriterFactory.PARAM_DATA_WRITER_CLASS_NAME,
        SumBasicDataWriter.class.getName(),
        DirectoryDataWriterFactory.PARAM_OUTPUT_DIRECTORY,
        this.modelDirectory.getPath(),
        SumBasicAnnotator.PARAM_TOKEN_FIELD,
        this.tokenField.name(),
        SumBasicAnnotator.PARAM_STOPWORDS_URI,
        stopwordsFile.toURI()));

    // Save off xmis for re-reading
    builder.add(AnalysisEngineFactory.createPrimitiveDescription(
        XMIWriter.class,
        XMIAnnotator.PARAM_XMI_DIRECTORY,
        xmiDirectory.getPath()));

    return builder;
  }

  public AggregateBuilder buildExtractAggregate() throws ResourceInitializationException {
    AggregateBuilder builder = new AggregateBuilder();

    builder.add(AnalysisEngineFactory.createPrimitiveDescription(
        XMIReader.class,
        XMIAnnotator.PARAM_XMI_DIRECTORY,
        this.xmiDirectory));

    // This will extract the features for summarization
    builder.add(AnalysisEngineFactory.createPrimitiveDescription(
        SumBasicAnnotator.class,
        CleartkAnnotator.PARAM_IS_TRAINING,
        false,
        GenericJarClassifierFactory.PARAM_CLASSIFIER_JAR_PATH,
        new File(this.modelDirectory, "model.jar").getPath(),
        SumBasicAnnotator.PARAM_TOKEN_FIELD,
        this.tokenField.name(),
        SumBasicAnnotator.PARAM_STOPWORDS_URI,
        this.stopwordsFile.toURI()));

    if (this.sentencesOutFile != null && this.outputSentences) {
      builder.add(SummarySentenceWriterAnnotator.getDescription(sentencesOutFile, this.outputScores));
    }
    return builder;
  }

  public void setSentencesOutFile(File file, boolean writeScores) {
    this.sentencesOutFile = file;
    this.outputSentences = true;
    this.outputScores = writeScores;
  }

  public static class Options extends Options_ImplBase {

    @Option(
        name = "--max-num-sentences",
        usage = "Specifies the maximum number of sentences to extract in the summary")
    public int maxNumSentences = 10;

    @Option(name = "--seen-words-prob", usage = "Specify the probability for seen words.")
    public double seenWordsProbability = 0.0001;

    @Option(
        name = "--composition-function",
        usage = "Specifies how word probabilities are combined (AVERAGE|SUM|PRODUCT, default=AVERAGE)")
    public CompositionFunctionType cfType = CompositionFunctionType.AVERAGE;

    @Option(
        name = "--token-field",
        usage = "Specifies what kind of token is used for summarization, (COVERED_TEXT|STEM|LEMMA, default=COVERED_TEXT)")
    public TokenField tokenField = TokenField.COVERED_TEXT;

    @Option(name = "--stopwords-file", usage = "Path to whitespace delimited stopwords text file")
    public File stopwordsFile = new File("src/main/resources/stopwords.txt");

    @Option(name = "--documents-dir", usage = "Path to documents to summarize")
    public File documentsDir = new File("src/test/resources/test_documents");

    @Option(name = "--model-dir", usage = "Path for saving model data")
    public File modelDir = new File("target/models");

    @Option(
        name = "--xmi-dir",
        usage = "Path for saving intermediate cas xmi files.  Leave unspecified for a temporary directory")
    public File xmiDir = Files.createTempDir();

    @Option(name = "sentencesOutFile", usage = "Path to the output file")
    public File sentencesOutFile = new File("target/sentences.out");

    @Option(name = "outputScores", usage = "Path to the output file")
    public Boolean outputScores = false;

  }

  public static void main(String[] args) throws Exception {
    Options options = new Options();
    options.parseOptions(args);

    SumBasic summarizer = new SumBasic(
        options.documentsDir,
        options.modelDir,
        options.xmiDir,
        options.stopwordsFile,
        SumBasicAnnotator.TokenField.COVERED_TEXT);
    summarizer.setSentencesOutFile(options.sentencesOutFile, options.outputScores);

    System.out.println("Training");
    summarizer.train();

    System.out.println("Extracting sentences");
    summarizer.extract();
  }

  // Following XMI readers and writers are used to save re-running the proprocessing during
  // summarization extraction.
  public static abstract class XMIAnnotator extends JCasAnnotator_ImplBase {

    @ConfigurationParameter(mandatory = true)
    protected File xmiDirectory;

    public static final String PARAM_XMI_DIRECTORY = ConfigurationParameterFactory.createConfigurationParameterName(
        XMIAnnotator.class,
        "xmiDirectory");

    protected File getFile(JCas jCas) throws AnalysisEngineProcessException {
      File origFile = new File(ViewURIUtil.getURI(jCas));
      String ext = Files.getFileExtension(origFile.getName());
      String xmi = origFile.getName().replaceAll("\\." + ext + "$", "");
      return new File(this.xmiDirectory, xmi + ".xmi");
    }

  }

  public static class XMIReader extends XMIAnnotator {

    @Override
    public void process(JCas jCas) throws AnalysisEngineProcessException {
      try {
        FileInputStream stream = new FileInputStream(this.getFile(jCas));
        try {
          XmiCasDeserializer.deserialize(stream, jCas.getCas());
        } finally {
          stream.close();
        }
      } catch (SAXException e) {
        throw new AnalysisEngineProcessException(e);
      } catch (IOException e) {
        throw new AnalysisEngineProcessException(e);
      }
    }
  }

  public static class XMIWriter extends XMIAnnotator {

    @Override
    public void initialize(UimaContext context) throws ResourceInitializationException {
      super.initialize(context);
      if (!this.xmiDirectory.exists()) {
        this.xmiDirectory.mkdirs();
      }
    }

    @Override
    public void process(JCas jCas) throws AnalysisEngineProcessException {
      XmiCasSerializer ser = new XmiCasSerializer(jCas.getTypeSystem());
      try {
        FileOutputStream stream = new FileOutputStream(this.getFile(jCas));
        try {
          ser.serialize(jCas.getCas(), new XMLSerializer(stream, false).getContentHandler());
        } finally {
          stream.close();
        }
      } catch (SAXException e) {
        throw new AnalysisEngineProcessException(e);
      } catch (IOException e) {
        throw new AnalysisEngineProcessException(e);
      }
    }
  }

}