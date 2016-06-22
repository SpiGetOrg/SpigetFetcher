package org.spiget.fetcher.parser;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.spiget.data.resource.ListedResource;
import org.spiget.data.resource.Resource;

import java.util.ArrayList;
import java.util.List;

public class ResourcePageParser {

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

				resource.setDescription(descriptionText.html());
			}
		}

		return resource;
	}

	public Resource parse(Document document, int id) {
		return parse(document, new ListedResource(id));
	}

}
