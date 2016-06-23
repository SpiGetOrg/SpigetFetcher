package org.spiget.fetcher.parser;

import org.jsoup.nodes.Element;
import org.spiget.data.resource.Rating;
import org.spiget.data.resource.version.ResourceVersion;

import static org.spiget.fetcher.parser.ParserUtil.*;

public class ResourceVersionItemParser {

	/**
	 * Parses resource version items
	 *
	 * @param versionItem &lt;tr class="dataRow "&gt;
	 * @return the parsed version
	 */
	public ResourceVersion parse(Element versionItem) {
		Element version = versionItem.select("td.version").first();// <td class="version">1.5</td>
		Element releaseDate = versionItem.select("td.releaseDate").first();// <td class="releaseDate"><abbr class="DateTime" data-time="1466633628" data-diff="4835" data-datestring="Jun 22, 2016" data-timestring="11:13 PM" title="Jun 22, 2016 at 11:13 PM">Yesterday at 11:13 PM</abbr></td>
		Element downloads = versionItem.select("td.downloads").first();// <td class="downloads">2</td>
		Element rating = versionItem.select("td.rating").first();
		Element download = versionItem.select("td.download").first();// <td class="dataOptions download"><a href="resources/example.12345/download?version=1234" class="secondaryContent">Download</a></td>

		ResourceVersion resourceVersion = new ResourceVersion(version.text());
		resourceVersion.setDownloads(Integer.parseInt(stringToInt(downloads.text())));

		{
			Element releaseDateTime = abbrOrSpan(releaseDate, ".DateTime");
			resourceVersion.setReleaseDate(parseTimeOrTitle(releaseDateTime));
		}
		{
			Element ratings = rating.select("span.ratings").first();// <span class="ratings" title="3.00">
			Element ratingsHint = rating.select("span.Hint").first();// <span class="Hint">1 rating</span>
			resourceVersion.setRating(new Rating(Integer.parseInt(ratingsHint.text().split(" ")[0]), Float.parseFloat(ratings.attr("title"))));
		}
		{
			Element downloadLink = download.select("a").first();
			resourceVersion.setUrl(downloadLink.attr("href"));
		}

		return resourceVersion;
	}

}
