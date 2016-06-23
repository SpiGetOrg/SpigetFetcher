package org.spiget.fetcher;

public enum FetchMode {

	/**
	 * Loads all resources from the specified amount of list-pages, loads the data from the source page and loads data from the author page
	 */
	FULL(true, true),
	/**
	 * Skips the resource versions update
	 */
	NO_VERSIONS(true,false),
	/**
	 * Only loads resources from the specified amount of list-pages, without getting the full data for resources or authors
	 */
	LIST(false, true);

	private boolean updateResource;
	private boolean updateResourceVersions;

	FetchMode(boolean updateResource, boolean updateResourceVersions) {
		this.updateResource = updateResource;
		this.updateResourceVersions = updateResourceVersions;
	}

	public boolean isUpdateResource() {
		return updateResource;
	}

	public boolean isUpdateResourceVersions() {
		return updateResourceVersions;
	}
}
