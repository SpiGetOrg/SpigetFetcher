package org.spiget.fetcher.parser;

import lombok.extern.log4j.Log4j2;
import org.jsoup.nodes.Element;
import org.spiget.data.author.ListedAuthor;
import org.spiget.data.category.ListedCategory;
import org.spiget.data.resource.ListedResource;
import org.spiget.data.resource.ResourceRating;
import org.spiget.data.resource.SpigetIcon;
import org.spiget.data.resource.version.ListedResourceVersion;

import java.io.IOException;

import static org.spiget.fetcher.parser.ParserUtil.*;

/**
 * Parser for items of the resources list
 */
@Log4j2
public class ResourceListItemParser {

	/**
	 * Parses a resource item.
	 *
	 * @param resourceItem &lt;li class="resourceListItem" id="resource-1234"&gt;
	 * @return the parsed item
	 */
	public ListedResource parse(Element resourceItem) {
		ListedResource listedResource = new ListedResource(Integer.parseInt(resourceItem.id().replace("resource-", "")));// <li class="resourceListItem visible " id="resource-12345">

		{
			Element resourceTitle = resourceItem.select("h3.title").first();
			Element resourceLink = resourceTitle.getElementsByTag("a").first();// <a href="resources/example-resource.12345/">Example Resource</a>
			Element resourceVersion = resourceTitle.select("span.version").first();// <span class="version">1.2.3</span>

			listedResource.setName(resourceLink.text());
			listedResource.setVersion(new ListedResourceVersion(resourceVersion.text(), 0L));// set the date to 0 here, will be updated when parsing the update date
		}

		{
			Element resourceDetails = resourceItem.select("div.resourceDetails").first();
			{
				Element resourceAuthor = resourceDetails.select("a.username").first();// <a href="members/example.1234/" class="username" dir="auto">Example</a>
				listedResource.setAuthor(new ListedAuthor(Integer.parseInt(extractIdFromUrl(resourceAuthor.attr("href"))), resourceAuthor.text(), null));
			}
			{
				Element resourceReleaseDate = abbrOrSpan(resourceDetails, ".DateTime");// <span class="DateTime" title="May 27, 2016 at 5:20 PM">May 27, 2016</span>
				listedResource.setReleaseDate(Long.parseLong(resourceReleaseDate.attr("data-time")));
			}
			{
				Element resourceCategory = null;
				for (Element element : resourceDetails.getAllElements()) {
					if (element.hasAttr("href") && element.attr("href").startsWith("resources/categories/")) {
						resourceCategory = element;
						break;
					}
				}
				if (resourceCategory != null) {// <a href="resources/categories/misc.16/">Misc</a>
					listedResource.setCategory(new ListedCategory(Integer.parseInt(extractIdFromUrl(resourceCategory.attr("href"))), resourceCategory.text()));
				}
			}
		}

		{// Load the icons later, so we can modify the author object
			Element resourceImage = resourceItem.select("div.resourceImage").first();

			{
				Element resourceIcon = resourceImage.select("a.resourceIcon").first();
				Element resourceIconImage = resourceIcon.select("img").first();// <img src="data/resource_icons/12/1234.jpg?12345" alt="">

				String iconSource = resourceIconImage.attr("src");
				String iconData = "";
				try {
					iconData = iconToBase64(iconSource);
				} catch (IOException | InterruptedException e) {
					log.warn("Failed to download icon data for #" + listedResource.getId(), e);
				}
				listedResource.setIcon(new SpigetIcon(iconSource, iconData));
			}
			{
				Element resourceAvatar = resourceImage.select("a.avatar").first();
				Element resourceAvatarImage = resourceAvatar.select("img").first();// <img src="data/avatars/s/54/54321.jpg?54321" width="48" height="48" alt="example">

				String avatarSource = resourceAvatarImage.attr("src");
				String avatarData = "";
				try {
					avatarData = iconToBase64(avatarSource);
				} catch (IOException | InterruptedException e) {
					log.warn("Failed to download avatar for #" + listedResource.getId(), e);
				}
				listedResource.getAuthor().setIcon(new SpigetIcon(avatarSource, avatarData));
			}
		}

		{
			Element resourceTagLine = resourceItem.select("div.tagLine").first();
			listedResource.setTag(resourceTagLine.text());
		}

		{
			Element resourceStats = resourceItem.select("div.resourceStats").first();
			{
				Element resourceRatingContainer = resourceStats.select("div.rating").first();
				{
					Element resourceRatings = resourceRatingContainer.select("span.ratings").first();// <span class="ratings" title="4.90">
					Element resourceRatingsHint = resourceRatingContainer.select("span.Hint").first();// <span class="Hint">10 ratings</span>
					listedResource.setResourceRating(new ResourceRating(Integer.parseInt(stringToInt(resourceRatingsHint.text().split(" ")[0])), Float.parseFloat(resourceRatings.attr("title"))));
				}
			}
			{
				Element resourceDownloads = resourceStats.select("dl.resourceDownloads").first();
				Element resourceDownloadNumber = resourceDownloads.select("dd").first();// <dd>51</dd>
				listedResource.setDownloads(Integer.parseInt(stringToInt(resourceDownloadNumber.text())));
			}
			{
				Element resourceUpdated = abbrOrSpan(resourceStats, ".DateTime");
				long updateDate = Long.parseLong(resourceUpdated.attr("data-time"));// <abbr class="DateTime" data-time="1466598083" data-diff="12" data-datestring="Jun 22, 2016" data-timestring="2:21 PM" title="Jun 22, 2016 at 2:21 PM">3 minutes ago</abbr>
				listedResource.setUpdateDate(updateDate);
				listedResource.getVersion().setReleaseDate(updateDate);// Update the date for the previously set version
			}
		}

		return listedResource;
	}

}
