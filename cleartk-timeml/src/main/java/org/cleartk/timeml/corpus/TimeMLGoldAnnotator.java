/** 
 * Copyright (c) 2007-2008, Regents of the University of Colorado 
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
package org.cleartk.timeml.corpus;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.CASException;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.Level;
import org.cleartk.timeml.TimeMLComponents;
import org.cleartk.timeml.TimeMLViewName;
import org.cleartk.timeml.type.Anchor;
import org.cleartk.timeml.type.Event;
import org.cleartk.timeml.type.TemporalLink;
import org.cleartk.timeml.type.Text;
import org.cleartk.timeml.type.Time;
import org.cleartk.timeml.util.TimeMLUtil;
import org.cleartk.util.ViewURIUtil;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.uimafit.component.JCasAnnotator_ImplBase;
import org.uimafit.descriptor.ConfigurationParameter;
import org.uimafit.descriptor.SofaCapability;
import org.uimafit.factory.AnalysisEngineFactory;
import org.uimafit.factory.ConfigurationParameterFactory;

/**
 * <br>
 * Copyright (c) 2007-2008, Regents of the University of Colorado <br>
 * All rights reserved.
 * 
 * 
 * @author Steven Bethard
 * 
 */
@SofaCapability(inputSofas = { TimeMLViewName.TIMEML, CAS.NAME_DEFAULT_SOFA })
public class TimeMLGoldAnnotator extends JCasAnnotator_ImplBase {

  public static final String PARAM_LOAD_TLINKS = ConfigurationParameterFactory
      .createConfigurationParameterName(TimeMLGoldAnnotator.class, "loadTlinks");

  @ConfigurationParameter(description = "when false indicates that annotation should not be created for TLINKs (though annotations will still be created for TIMEX3s, EVENTs, etc.).", defaultValue = "true")
  private boolean loadTlinks;

  public static AnalysisEngineDescription getDescription() throws ResourceInitializationException {
    return AnalysisEngineFactory.createPrimitiveDescription(
        TimeMLGoldAnnotator.class,
        TimeMLComponents.TYPE_SYSTEM_DESCRIPTION);
  }

  public static AnalysisEngineDescription getDescriptionNoTLINKs()
      throws ResourceInitializationException {
    return AnalysisEngineFactory.createPrimitiveDescription(
        TimeMLGoldAnnotator.class,
        TimeMLComponents.TYPE_SYSTEM_DESCRIPTION,
        PARAM_LOAD_TLINKS,
        false);
  }

  @Override
  public void initialize(UimaContext context) throws ResourceInitializationException {
    super.initialize(context);
  }

  @Override
  public void process(JCas jCas) throws AnalysisEngineProcessException {
    JCas timemlView;
    JCas initialView;
    try {
      timemlView = jCas.getView(TimeMLViewName.TIMEML);
      initialView = jCas.getView(CAS.NAME_DEFAULT_SOFA);
    } catch (CASException e) {
      throw new AnalysisEngineProcessException(e);
    }

    String timeML = timemlView.getDocumentText();
    SAXBuilder builder = new SAXBuilder();
    builder.setDTDHandler(null);
    Element root;
    try {
      Document doc = builder.build(new StringReader(timeML));
      root = doc.getRootElement();
    } catch (JDOMException e) {
      getContext().getLogger().log(
          Level.SEVERE,
          "problem parsing document: " + ViewURIUtil.getURI(jCas));
      throw new AnalysisEngineProcessException(e);
    } catch (IOException e) {
      throw new AnalysisEngineProcessException(e);
    }

    StringBuffer textBuffer = new StringBuffer();
    Map<String, Anchor> anchors = new HashMap<String, Anchor>();
    this.addAnnotations(initialView, root, textBuffer, anchors);
    initialView.setDocumentText(textBuffer.toString());
  }

  private void addAnnotations(
      JCas jCas,
      Element element,
      StringBuffer textBuffer,
      Map<String, Anchor> anchors) throws AnalysisEngineProcessException {
    int startOffset = textBuffer.length();
    for (Object content : element.getContent()) {
      if (content instanceof org.jdom.Text) {
        textBuffer.append(((org.jdom.Text) content).getText());
      } else if (content instanceof Element) {
        this.addAnnotations(jCas, (Element) content, textBuffer, anchors);
      }
    }
    int endOffset = textBuffer.length();

    if (element.getName().equals("TIMEX3")) {
      Time time = new Time(jCas, startOffset, endOffset);
      TimeMLUtil.copyAttributes(element, time, jCas);
      anchors.put(time.getId(), time);
      time.addToIndexes();
    } else if (element.getName().equals("EVENT")) {
      Event event = new Event(jCas, startOffset, endOffset);
      TimeMLUtil.copyAttributes(element, event, jCas);
      anchors.put(event.getId(), event);
      event.addToIndexes();
    } else if (element.getName().equals("MAKEINSTANCE")) {
      String eventID = element.getAttributeValue("eventID");
      String eventInstanceID = element.getAttributeValue("eiid");
      Event event = (Event) anchors.get(eventID);
      anchors.put(eventInstanceID, event);
      eventInstanceID = event.getEventInstanceID();
      if (eventInstanceID == null) {
        TimeMLUtil.copyAttributes(element, event, jCas);
      } else {
        TimeMLUtil.removeInconsistentAttributes(element, event, jCas);
        event.setId(eventID);
        event.setEventInstanceID(eventInstanceID);
      }
    } else if (element.getName().equals("TLINK") && this.loadTlinks) {
      TemporalLink temporalLink = new TemporalLink(jCas, startOffset, endOffset);
      TimeMLUtil.copyAttributes(element, temporalLink, jCas);
      String sourceID = this.getOneOf(element, "eventInstanceID", "eventID", "timeID");
      String targetID = this.getOneOf(
          element,
          "relatedToEventInstance",
          "relatedToEvent",
          "relatedToTime");
      Anchor source = this.getAnchor(jCas, anchors, sourceID);
      Anchor target = this.getAnchor(jCas, anchors, targetID);
      if (source instanceof Event) {
        temporalLink.setEventID(source.getId());
      }
      if (target instanceof Event) {
        temporalLink.setRelatedToEvent(target.getId());
      }
      temporalLink.setSource(source);
      temporalLink.setTarget(target);
      temporalLink.addToIndexes();
    } else if (element.getName().equals("TEXT")) {
      Text text = new Text(jCas, startOffset, endOffset);
      text.addToIndexes();
    }
  }

  private String getOneOf(Element element, String... attributeNames) {
    for (String name : attributeNames) {
      String result = element.getAttributeValue(name);
      if (result != null) {
        return result;
      }
    }
    throw new RuntimeException(String.format(
        "unable to find in %s any of the following attributes: %s",
        element,
        Arrays.asList(attributeNames)));
  }

  private Anchor getAnchor(JCas jCas, Map<String, Anchor> anchors, String id)
      throws AnalysisEngineProcessException {
    Anchor anchor = anchors.get(id);
    if (anchor == null) {
      throw new RuntimeException(String.format(
          "%s: no anchor for id %s",
          ViewURIUtil.getURI(jCas),
          id));
    }
    return anchor;
  }

  public void setLoadTlinks(boolean loadTLINKs) {
    this.loadTlinks = loadTLINKs;
  }
}
