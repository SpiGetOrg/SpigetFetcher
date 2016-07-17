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

		ResourceVersion resourceVersion;
		{
			Element downloadLink = download.select("a").first();
			String href = downloadLink.attr("href");
			resourceVersion = new ResourceVersion(Integer.parseInt(extractIdFromUrl(href, PARAM_URL_ID)), version.text());
			resourceVersion.setUrl(href);

			resourceVersion.setDownloads(Integer.parseInt(stringToInt(downloads.text())));
		}
		{
			Element releaseDateTime = abbrOrSpan(releaseDate, ".DateTime");
			resourceVersion.setReleaseDate(parseTimeOrTitle(releaseDateTime));
		}
		{
			Rating rating1 = new RatingParser().parse(rating);
			resourceVersion.setRating(rating1);
		}

		return resourceVersion;
	}

}
