package org.spiget.fetcher.parser;

import org.jsoup.nodes.Element;
import org.spiget.data.resource.update.ResourceUpdate;

public class ResourceUpdateItemParer {

	public ResourceUpdate parse(Element resourceUpdateItem) {
		ResourceUpdate resourceUpdate = new ResourceUpdate(Integer.parseInt(resourceUpdateItem.id().split("-")[1]));
		Element textHeading = resourceUpdateItem.select("h2.textHeading").first();
		resourceUpdate.setTitle(textHeading.select("a").first().text());
		return resourceUpdate;
	}

}
