/** 
 * Copyright (c) 2010, Regents of the University of Colorado 
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
package org.cleartk.test;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

import org.apache.uima.UIMAException;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.collection.CollectionReader;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.uimafit.component.NoOpAnnotator;
import org.uimafit.factory.AnalysisEngineFactory;
import org.uimafit.factory.JCasFactory;
import org.uimafit.factory.TypeSystemDescriptionFactory;
import org.uimafit.pipeline.JCasIterable;

/**
 * <br>
 * Copyright (c) 2010, Regents of the University of Colorado <br>
 * All rights reserved.
 * 
 * @author Philip Ogren
 */

public abstract class CleartkTestBase {

  /**
   * A {@link Logger} instance associated with the class, intended for use by subclasses.
   */
  protected Logger logger;

  /**
   * The name of the property that can be set to skip some tests. The property value will be parsed
   * as a comma-separated list of values, where each value can designate one type of test to skip,
   * e.g. {@value #LONG_TESTS_PROPERTY_VALUE} or {@value #BIG_MEMORY_TESTS_PROPERTY_VALUE}. Example
   * usage: <br/>
   * <code>-Dcleartk.skipTests=long,bigMem</code>
   */
  public static final String SKIP_TESTS_PROPERTY = "cleartk.skipTests";

  /**
   * Value for the {@link #SKIP_TESTS_PROPERTY} property that indicates that long-running tests
   * should be disabled. Current value: {@value #LONG_TESTS_PROPERTY_VALUE}.
   */
  public static final String LONG_TESTS_PROPERTY_VALUE = "long";

  /**
   * Value for the {@link #SKIP_TESTS_PROPERTY} property that indicates that tests requiring a lot
   * of memory should be disabled. Current value: {@value #BIG_MEMORY_TESTS_PROPERTY_VALUE}.
   */
  public static final String BIG_MEMORY_TESTS_PROPERTY_VALUE = "bigMem";

  private boolean skipLongTests;

  private boolean skipBigMemoryTests;

  public CleartkTestBase() {
    super();
    this.logger = Logger.getLogger(this.getClass().getName());
    String skipTests = System.getProperty(SKIP_TESTS_PROPERTY);
    if (skipTests != null) {
      for (String skip : skipTests.split("\\s*[,]\\s*")) {
        if (skip.equals(LONG_TESTS_PROPERTY_VALUE)) {
          this.skipLongTests = true;
        } else if (skip.equals(BIG_MEMORY_TESTS_PROPERTY_VALUE)) {
          this.skipBigMemoryTests = true;
        } else {
          throw new IllegalArgumentException(String.format(
              "expected %s to be one of [%s, %s], found %s",
              SKIP_TESTS_PROPERTY,
              LONG_TESTS_PROPERTY_VALUE,
              BIG_MEMORY_TESTS_PROPERTY_VALUE,
              skip));
        }
      }
    }
  }

  /**
   * Subclasses should call this method at the beginning of a test that will take a long time to
   * run. Immediately after calling this method, they should also call
   * <code>this.logger.info({@link #LONG_TEST_MESSAGE})</code>.
   */
  protected void assumeLongTestsEnabled() {
    // note that we can't log the message here as well, or it the log will display the wrong method
    Assume.assumeTrue(!this.skipLongTests);
  }

  /**
   * A message indicating that the current test runs for a long time, and giving instructions how to
   * skip it. Should be logged immediately after calling {@link #assumeLongTestsEnabled()}.
   */
  protected static final String LONG_TEST_MESSAGE = String.format(
      "This test takes a long time to run. To skip it, set -D%s=%s",
      SKIP_TESTS_PROPERTY,
      LONG_TESTS_PROPERTY_VALUE);

  /**
   * Subclasses should call this method at the beginning of a test that will take a long time to
   * run. Immediately after calling this method, they should also call
   * <code>this.logger.info({@link #BIG_MEMORY_TEST_MESSAGE})</code>.
   */
  protected void assumeBigMemoryTestsEnabled() {
    // note that we can't log the message here as well, or it the log will display the wrong method
    Assume.assumeTrue(!this.skipBigMemoryTests);
  }

  /**
   * A message indicating that the current test requires a lot of memory, and giving instructions
   * how to skip it. Should be logged immediately after calling
   * {@link #assumeBigMemoryTestsEnabled()}.
   */
  public static final String BIG_MEMORY_TEST_MESSAGE = String.format(
      "This test requires a lot of memory. To skip it, set -D%s=%s",
      SKIP_TESTS_PROPERTY,
      BIG_MEMORY_TESTS_PROPERTY_VALUE);

  protected JCas jCas;

  protected TypeSystemDescription typeSystemDescription;

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  protected File outputDirectory;

  protected String outputDirectoryName;

  @Before
  public void setUp() throws Exception {
    typeSystemDescription = TypeSystemDescriptionFactory.createTypeSystemDescription(getTypeSystemDescriptorNames());
    jCas = JCasFactory.createJCas(typeSystemDescription);
    outputDirectory = folder.newFolder("output");
    outputDirectoryName = outputDirectory.getPath();
  }

  public abstract String[] getTypeSystemDescriptorNames();

  public int getCollectionReaderCount(CollectionReader reader) throws UIMAException, IOException {

    AnalysisEngine aeAdapter = AnalysisEngineFactory.createPrimitive(
        NoOpAnnotator.class,
        typeSystemDescription);

    int count = 0;
    JCasIterable jCases = new JCasIterable(reader, aeAdapter);
    for (@SuppressWarnings("unused")
    JCas jcs : jCases) {
      count++;
    }
    return count;
  }

  public void testCollectionReaderCount(CollectionReader reader, int expectedCount)
      throws UIMAException, IOException {
    assertEquals(expectedCount, getCollectionReaderCount(reader));
  }

}
