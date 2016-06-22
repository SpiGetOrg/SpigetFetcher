package org.spiget.fetcher.test;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.Test;
import org.spiget.data.author.ListedAuthor;
import org.spiget.data.category.ListedCategory;
import org.spiget.data.resource.ListedResource;
import org.spiget.data.resource.Resource;
import org.spiget.data.resource.version.ListedResourceVersion;
import org.spiget.fetcher.parser.ParserUtil;
import org.spiget.fetcher.parser.ResourceListItemParser;
import org.spiget.fetcher.parser.ResourcePageParser;

import java.io.IOException;
import java.util.Arrays;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class ParseTest {

	@Test
	public void systemTimezoneTest() {
		String timezoneString = TimeZone.getDefault().getDisplayName();
		System.out.println("Timezone is " + timezoneString);
		assertEquals("Central European Time", timezoneString);// TODO: this obviously won't work everywhere
	}

	@Test
	public void dateTimeParseTest() {
		String dateTime = "May 27, 2016 at 5:20 PM " + TimeZone.getDefault().getDisplayName();
		long unix = ParserUtil.parseDateTimeToLong(dateTime);

		assertEquals(1464362400, unix);
	}

	@Test
	public void resourceItemParseTest() throws IOException {
		String html = org.apache.commons.io.IOUtils.toString(ParseTest.class.getResourceAsStream("/HologramAPI-ResourceItem.html"));
		Document document = Jsoup.parse(html);
		Element resourceItem = document.select("li.resourceListItem").first();

		ListedResource parsed = new ResourceListItemParser().parse(resourceItem);
		assertEquals(6766, parsed.getId());
		assertEquals("[API] HologramAPI [1.7 | 1.8 | 1.9]", parsed.getName());
		assertEquals("1.6.0", parsed.getVersion().getName());
		assertEquals(6643, parsed.getAuthor().getId());
		assertEquals("inventivetalent", parsed.getAuthor().getName());
	}

	@Test
	public void resourcePageParseTest() throws IOException {
		String html = org.apache.commons.io.IOUtils.toString(ParseTest.class.getResourceAsStream("/InventoryScroll-ResourcePage.html"));
		Document document = Jsoup.parse(html);

		ListedResource base = new ListedResource(21714, "InventoryScroll");//Would be provided by the resource list fetcher
		base.setCategory(new ListedCategory(22, "Mechanics"));
		base.setVersion(new ListedResourceVersion("1.1.0"));
		base.setAuthor(new ListedAuthor(6643, "inventivetalent"));

		Resource parsed = new ResourcePageParser().parse(document, base);
		assertEquals(Arrays.asList("1.7", "1.8", "1.9"), parsed.getTestedVersions());
		assertEquals("inventivetalent", parsed.getAuthor().getName());
		assertNotNull(parsed.getDescription());
		assertTrue(parsed.getDescription().startsWith("This plugin allows you to swap the items in your hotbar with other items in your inventory."));
	}

}
