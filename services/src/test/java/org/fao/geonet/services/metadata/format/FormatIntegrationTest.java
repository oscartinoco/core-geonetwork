package org.fao.geonet.services.metadata.format;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import jeeves.server.context.ServiceContext;
import org.eclipse.jetty.util.IO;
import org.fao.geonet.constants.Params;
import org.fao.geonet.domain.MetadataType;
import org.fao.geonet.domain.ReservedGroup;
import org.fao.geonet.kernel.GeonetworkDataDirectory;
import org.fao.geonet.kernel.SchemaManager;
import org.fao.geonet.services.AbstractServiceIntegrationTest;
import org.fao.geonet.utils.Xml;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URL;
import java.util.List;
import javax.annotation.Nullable;

import static org.fao.geonet.domain.Pair.read;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class FormatIntegrationTest extends AbstractServiceIntegrationTest {

    @Autowired
    private GeonetworkDataDirectory dataDirectory;
    @Autowired
    private SchemaManager schemaManager;

    @Test
    public void testExec() throws Exception {
        final ServiceContext serviceContext = createServiceContext();
        loginAsAdmin(serviceContext);

        final Element sampleMetadataXml = getSampleMetadataXml();
        final ByteArrayInputStream stream = new ByteArrayInputStream(Xml.getString(sampleMetadataXml).getBytes("UTF-8"));
        final int id =  importMetadataXML(serviceContext, "uuid", stream, MetadataType.METADATA,
                ReservedGroup.all.getId(), Params.GENERATE_UUID);
        final String schema = schemaManager.autodetectSchema(sampleMetadataXml);

        final ListFormatters listService = new ListFormatters();
        final Element formattersEl = listService.exec(createParams(read("schema", schema)), serviceContext);

        final List<String> formatters = Lists.transform(formattersEl.getChildren("formatter"), new Function() {
            @Nullable
            @Override
            public String apply(@Nullable Object input) {
                return ((Element)input).getText();
            }
        });

        for (String formatter : formatters) {
            final Format formatService = new Format();
            final Element view = formatService.exec(createParams(read("id", id), read("xsl", formatter)), serviceContext);
            view.setName("body");
            Element html = new Element("html").addContent(view);
            assertFalse(html.getChildren().isEmpty());
        }
    }

    @Test
    public void testExecXslt() throws Exception {
        final ServiceContext serviceContext = createServiceContext();
        loginAsAdmin(serviceContext);

        final Element sampleMetadataXml = getSampleMetadataXml();
        final ByteArrayInputStream stream = new ByteArrayInputStream(Xml.getString(sampleMetadataXml).getBytes("UTF-8"));
        final int id =  importMetadataXML(serviceContext, "uuid", stream, MetadataType.METADATA,
                ReservedGroup.all.getId(), Params.GENERATE_UUID);
        final String formatterName = "xsl-test-formatter";
        final URL testFormatterViewFile = FormatIntegrationTest.class.getResource(formatterName+"/view.xsl");
        final File testFormatter = new File(testFormatterViewFile.getFile()).getParentFile();
        IO.copy(testFormatter, new File(this.dataDirectory.getFormatterDir(), formatterName));
        final String functionsXslName = "functions.xsl";
        IO.copy(new File(testFormatter.getParentFile(), functionsXslName), new File(this.dataDirectory.getFormatterDir(), functionsXslName));

        final Format formatService = new Format();
        final Element view = formatService.exec(createParams(read("id", id), read("xsl", formatterName)), serviceContext);

        assertEqualsText("fromFunction", view, "*//p");
    }

    @Test
    public void testExecGroovy() throws Exception {
        final ServiceContext serviceContext = createServiceContext();
        loginAsAdmin(serviceContext);

        final Element sampleMetadataXml = getSampleMetadataXml();
        final ByteArrayInputStream stream = new ByteArrayInputStream(Xml.getString(sampleMetadataXml).getBytes("UTF-8"));
        final int id =  importMetadataXML(serviceContext, "uuid", stream, MetadataType.METADATA,
                ReservedGroup.all.getId(), Params.GENERATE_UUID);
        final String formatterName = "groovy-test-formatter";
        final URL testFormatterViewFile = FormatIntegrationTest.class.getResource(formatterName+"/view.groovy");
        final File testFormatter = new File(testFormatterViewFile.getFile()).getParentFile();
        IO.copy(testFormatter, new File(this.dataDirectory.getFormatterDir(), formatterName));
        final String groovySharedClasses = "groovy";
        IO.copy(new File(testFormatter.getParentFile(), groovySharedClasses), new File(this.dataDirectory.getFormatterDir(), groovySharedClasses));

        final Format formatService = new Format();
        final Element params = createParams(read("id", id), read("xsl", formatterName), read("h2IdentInfo", "true"));
        final Element view = formatService.exec(params, serviceContext);

        assertEquals("html", view.getName());
        assertNotNull("body", view.getChild("body"));

        // Check that the "handlers.add 'gmd:abstract', { el ->" correctly applied
        assertElement(view, "body//p[@class = 'abstract']/span[@class='label']", "Abstract", 1);
        assertElement(view, "body//p[@class = 'abstract']/span[@class='value']", "Abstract {uuid}", 1);

        // Check that the "handlers.add ~/...:title/, { el ->" correctly applied
        assertElement(view, "body//p[@class = 'title']/span[@class='label']", "Title", 1);
        assertElement(view, "body//p[@class = 'title']/span[@class='value']", "Title", 1);

        // Check that the "handlers.withPath ~/[^>]+>gmd:identificationInfo>.*extent/, Iso19139Functions.&handleExtent" correctly applied
        assertElement(view, "body//p[@class = 'formatter']", "fromFormatterGroovy", 1);

        // Check that the "handlers.withPath ~/[^>]+>gmd:identificationInfo>.*extent/, Iso19139Functions.&handleExtent" correctly applied
        assertElement(view, "body//p[@class = 'shared']", "fromSharedFunctions", 1);


        // Check that the "handlers.add ~/...:title/, { el ->" correctly applied
        assertElement(view, "body//p[@class = 'code']/span[@class='label']", "Unique resource identifier", 1);
        assertElement(view, "body//p[@class = 'code']/span[@class='value']", "WGS 1984", 1);

        // Check that the handlers.add 'gmd:CI_OnlineResource', { el -> handler is applied
        assertElement(view, "body//p[@class = 'online-resource']/h3", "OnLine resource", 1);
        assertElement(view, "body//p[@class = 'online-resource']/div/strong", "REPOM", 1);
        assertElement(view, "body//p[@class = 'online-resource']/div[@class='desc']", "", 1);
        assertElement(view, "body//p[@class = 'online-resource']/div[@class='linkage']/span[@class='label']", "URL:", 1);
        assertElement(view, "body//p[@class = 'online-resource']/div[@class='linkage']/span[@class='value']", "http://services.sandre.eaufrance.fr/geo/ouvrage", 1);

        // Check that the handler:
        //   handlers.add select: {el -> el.name() == 'gmd:identificationInfo' && f.param('h2IdentInfo').toBool()},
        //                processChildren: true, { el, childData ->
        // was applied
        assertElement(view, "*//div[@class = 'identificationInfo']/h2", "Data identification", 1);
    }

    private void assertElement(Element view, String onlineResourceHeaderXpath, String expected, int numberOfElements) throws JDOMException {
        assertEquals(Xml.getString(view), numberOfElements, Xml.selectNodes(view, onlineResourceHeaderXpath).size());
        assertEqualsText(expected, view, onlineResourceHeaderXpath);
    }


}