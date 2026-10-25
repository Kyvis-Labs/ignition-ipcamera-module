package com.kyvislabs.camera.gateway.config;

import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Description;

/**
 * A tag condition that starts recording ({@link RecordMode#TAG}) or drops a bookmark.
 *
 * @param condition one of {@code true}, {@code false}, {@code > n}, {@code >= n}, {@code < n},
 *                  {@code <= n}, {@code = v}, {@code != v}; blank means "truthy"
 */
public record TagTrigger(
    @Description("Full tag path, e.g. [default]Line1/Door/Open")
    String tagPath,
    @Description("Condition on the tag value: true, false, > n, >= n, < n, <= n, = v, != v. Blank = truthy.")
    String condition,
    @Description("Label written on the event or bookmark.")
    String label,
    @Description("Bookmarks only: seconds before the trigger to include.")
    Integer preSec,
    @Description("Bookmarks only: seconds after the trigger to include.")
    Integer postSec
) {
}
