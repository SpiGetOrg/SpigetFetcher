package org.spiget.fetcher.test;

import com.google.gson.JsonObject;
import org.junit.Test;
import org.spiget.client.SpigetClient;
import org.spiget.client.SpigetResponse;
import org.spiget.data.author.ListedAuthor;
import org.spiget.data.category.ListedCategory;
import org.spiget.data.resource.ListedResource;
import org.spiget.data.resource.Resource;
import org.spiget.data.resource.version.ListedResourceVersion;
import org.spiget.parser.ResourcePageParser;

import java.io.IOException;
import java.util.Base64;

import static org.junit.Assert.*;

public class RequestTest {

	public RequestTest() {
		SpigetClient.config = new JsonObject();
		SpigetClient.userAgent = "Spiget-v2-Test GoogleBot";
		SpigetClient.config.addProperty("request.userAgent", "Spiget-v2-Test GoogleBot");
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

	@Test
	public void premiumResourceRequestParseTest() throws IOException, InterruptedException {
		ListedResource base = new ListedResource(33956, "StaffMode");//Would be provided by the resource list fetcher
		base.setCategory(new ListedCategory(20, "Premium"));
		base.setVersion(new ListedResourceVersion(0, "4.9"));
		base.setAuthor(new ListedAuthor(113444, "ItzProtomPvP"));

		SpigetResponse response = SpigetClient.get("https://www.spigotmc.org/resources/staffmode.33956/");
		org.jsoup.nodes.Document document = response.getDocument();
		Resource parsed = new ResourcePageParser().parse(document, base);

		assertEquals(33956, parsed.getId());
		assertEquals("StaffMode", parsed.getName());
		assertNotNull(parsed.getDescription());
	}

}
