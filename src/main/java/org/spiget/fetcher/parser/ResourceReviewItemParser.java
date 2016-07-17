package org.spiget.fetcher.parser;

import org.jsoup.nodes.Element;
import org.spiget.data.author.ListedAuthor;
import org.spiget.data.resource.ResourceReview;
import org.spiget.data.resource.SpigetIcon;

import java.util.Base64;

import static org.spiget.fetcher.parser.ParserUtil.*;

public class ResourceReviewItemParser {

	public ResourceReview parse(Element resourceReviewItem) {
		ResourceReview review = new ResourceReview();

		{
			Element memberAvatar = resourceReviewItem.select("a.avatar").first();
			ListedAuthor reviewAuthor = new ListedAuthor(Integer.parseInt(extractIdFromUrl(memberAvatar.attr("href"), DOT_URL_ID)));
			reviewAuthor.setName(resourceReviewItem.attr("data-author"));
			SpigetIcon avatar = new IconParser().parse(memberAvatar);
			reviewAuthor.setIcon(avatar);

			review.setAuthor(reviewAuthor);
		}
		{
			Element messageInfo = resourceReviewItem.select("div.messageInfo").first();
			{
				Element rating = messageInfo.select("div.rating").first();
				review.setRating(new RatingParser().parseSingle(rating));
			}
			{
				Element version = abbrOrSpan(messageInfo, ".muted");
				String versionName = version.text().substring("Version: ".length());
				review.setVersion(versionName);
			}
			{
				Element baseHtml = messageInfo.select("blockquote.baseHtml").first();
				review.setMessage(Base64.getEncoder().encodeToString(baseHtml.html().getBytes()));
			}

			{
				Element messageMeta = messageInfo.select("div.messageMeta").first();
				review.setDate(parseTimeOrTitle(abbrOrSpan(messageMeta, ".DateTime")));
			}

			{
				Element messageResponse = messageInfo.select("ol.messageResponse").first();
				if (messageResponse != null) {
					Element blockQuote = messageResponse.select("blockquote").first();
					review.setResponseMessage(Base64.getEncoder().encodeToString(blockQuote.html().getBytes()));
				}
			}
		}

		return review;
	}

}
