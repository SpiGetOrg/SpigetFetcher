package org.spiget.fetcher.parser;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.spiget.data.resource.ListedResource;
import org.spiget.data.resource.Resource;
import org.spiget.data.resource.ResourceFile;
import org.spiget.fetcher.SpigetFetcher;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class ResourcePageParser {

	static boolean debug = SpigetFetcher.config.has("debug.parse.resource.page") && SpigetFetcher.config.get("debug.parse.resource.page").getAsBoolean();

	public Resource parse(Document document, ListedResource base) {
		Resource resource = new Resource(base);

		Element updateContainer = document.select("div.updateContainer").first();
		{
			Element descriptionText = updateContainer.select("blockquote.messageText").first();
			{
				Element customResourceFields = descriptionText.select("div.customResourceFields").first();
				if (customResourceFields != null) {
					{
						Element customResourceFieldVersions = customResourceFields.select("dl.customResourceFieldmc_versions").first();// <dl class="customResourceFieldmc_versions">
						if (customResourceFieldVersions != null) {
							Element versionList = customResourceFieldVersions.select("ul.plainList").first();
							List<String> testedVersions = new ArrayList<>();

							for (Element versionElement : versionList.select("li")) {
								testedVersions.add(versionElement.text());
							}

							resource.setTestedVersions(testedVersions);
						}
					}
					{
						Element customResourceFieldContributors = customResourceFields.select("dl.customResourceFieldcontributors").first();// <dl class="customResourceFieldcontributors">
						if (customResourceFieldContributors != null) {
							Element contributorsElement = customResourceFieldContributors.select("dd").first();// <dd>Example</dd>
							resource.setContributors(contributorsElement.text());
						}
					}

					customResourceFields.remove();// Remove so we only have the actual description left
				}

				{// Custom links
					for (Element linkElement : descriptionText.select("a")) {
						if (linkElement.text() != null && linkElement.text().length() > 0) {
							if (linkElement.hasAttr("href")) {
								resource.getLinks().put(("custom:" + linkElement.text()).replace('.', '\u002e'), linkElement.attr("href"));
							}
						}
					}
				}

				resource.setDescription(Base64.getEncoder().encodeToString(descriptionText.html().getBytes()));
			}
		}
		{
			Element downloadButton = document.select("label.downloadButton").first();
			Element innerLink = downloadButton.select("a.inner").first();
			Element minorText = innerLink.select("small.minorText").first();
			String[] minorTextSplit = minorText.text().split("\\s+");

			if (minorText.text().contains("external")) {// External
				resource.setFile(new ResourceFile("external", 0, "", innerLink.attr("href")));
				resource.setExternal(true);
			} else {
				resource.setFile(new ResourceFile(minorTextSplit[2], Float.parseFloat(minorTextSplit[0]), minorTextSplit[1], innerLink.attr("href")));// 32.6 KB .sk
			}
		}

		// Links
		{
			{// Discussion
				Element resourceTabDiscussion = document.select("li.resourceTabDiscussion").first();
				if (resourceTabDiscussion != null) {
					Element discussionLink = resourceTabDiscussion.select("a").first();
					resource.getLinks().put("discussion", discussionLink.attr("href"));
				}
			}
			{// Additional information
				Element resourceInfo = document.select("div.statsList#resourceInfo").first();
				if (resourceInfo != null) {
					Element footnote = resourceInfo.select("div.footnote").first();
					if (footnote != null) {
						Element footnoteLink = footnote.select("a").first();
						resource.getLinks().put("additionalInformation", footnoteLink.attr("href"));
					}
				}
			}
			{// Alternative support
				Element sidebar = document.select("div.sidebar").first();
				Element callToAction = sidebar.select("a.callToAction").first();
				if (callToAction != null) {
					String href = callToAction.attr("href");
					if (!href.startsWith("threads/")) {
						resource.getLinks().put("alternativeSupport", href);
					}// There's no alternative URL, only the discussion link
				}
			}
		}

		return resource;
	}

	public Resource parse(Document document, int id) {
		return parse(document, new ListedResource(id));
	}

}
