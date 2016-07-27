package org.spiget.fetcher.test;

import com.google.gson.JsonObject;
import org.junit.Test;
import org.spiget.client.SpigetClient;
import org.spiget.data.author.ListedAuthor;
import org.spiget.data.category.ListedCategory;
import org.spiget.data.resource.ListedResource;
import org.spiget.data.resource.Resource;
import org.spiget.data.resource.version.ListedResourceVersion;
import org.spiget.fetcher.parser.ResourcePageParser;

import java.io.IOException;
import java.util.Base64;

import static org.junit.Assert.*;

public class RequestTest {

	public RequestTest() {
		SpigetClient.config = new JsonObject();
		SpigetClient.userAgent = "Spiget-v2-Test";
		SpigetClient.config.addProperty("request.userAgent", "Spiget-v2-Test");
		SpigetClient.config.addProperty("debug.connections", false);
	}

	@Test
	public void resourceRequestParseTest() throws IOException, InterruptedException {
		ListedResource base = new ListedResource(21779, "SaturationPreview");//Would be provided by the resource list fetcher
		base.setCategory(new ListedCategory(22, "Mechanics"));
		base.setVersion(new ListedResourceVersion(0, "1.2.1"));
		base.setAuthor(new ListedAuthor(6643, "inventivetalent"));

		org.jsoup.nodes.Document document = SpigetClient.get("https://www.spigotmc.org/resources/saturationpreview.21779/").getDocument();
		Resource parsed = new ResourcePageParser().parse(document, base);

		assertEquals(21779, parsed.getId());
		assertEquals("SaturationPreview", parsed.getName());
		assertNotNull(parsed.getDescription());
		assertTrue(new String(Base64.getDecoder().decode(parsed.getDescription())).startsWith("This plugin shows you the amount of saturation a food item regenerates."));
	}

}
