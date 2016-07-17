package org.spiget.fetcher;

public enum FetchMode {

	/**
	 * Loads all resources from the specified amount of list-pages, loads the data from the source page and loads data from the author page
	 */
	FULL(true, true, true, true),
	/**
	 * Skips the resource versions update
	 */
	NO_VERSIONS(true, false, false, false),
	/**
	 * Only loads resources from the specified amount of list-pages, without getting the full data for resources or authors
	 */
	LIST(false, false, false, false);

	private boolean updateResource;
	private boolean updateResourceVersions;
	private boolean updateUpdates;
	private boolean updateReviews;

	FetchMode(boolean updateResource, boolean updateResourceVersions, boolean updateUpdates, boolean updateReviews) {
		this.updateResource = updateResource;
		this.updateResourceVersions = updateResourceVersions;
		this.updateUpdates = updateUpdates;
		this.updateReviews = updateReviews;
	}

	public boolean isUpdateResource() {
		return updateResource;
	}

	public boolean isUpdateResourceVersions() {
		return updateResourceVersions;
	}

	public boolean isUpdateUpdates() {
		return updateUpdates;
	}

	public boolean isUpdateReviews() {
		return updateReviews;
	}
}
