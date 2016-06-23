package org.spiget.fetcher.parser;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.spiget.data.resource.update.ResourceUpdate;

import java.util.Base64;

import static org.spiget.fetcher.parser.ParserUtil.abbrOrSpan;
import static org.spiget.fetcher.parser.ParserUtil.parseTimeOrTitle;

public class ResourceUpdateParser {

	public ResourceUpdate parse(Document document, ResourceUpdate base) {
		Element resourceUpdate = document.select("li.resourceUpdate").first();
		Element messageText = resourceUpdate.select("blockquote.messageText").first();
		Element datePermalink = resourceUpdate.select("a.datePermalink").first();
		Element updateDateTime = abbrOrSpan(datePermalink, ".DateTime");

		base.setDescription(Base64.getEncoder().encodeToString(messageText.html().getBytes()));
		base.setDate(parseTimeOrTitle(updateDateTime));
		return base;
	}

}
