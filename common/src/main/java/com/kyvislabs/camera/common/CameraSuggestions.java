package com.kyvislabs.camera.common;

/**
 * Keys of the Designer property dropdowns ({@code "extension": {"suggestion-source": ...}} in the
 * component schemas), registered by the designer hook with Perspective's
 * {@code PropertySuggestionSourceRegistry}.
 */
public final class CameraSuggestions {
    /** The gateway's configured camera names. */
    public static final String CAMERAS = "camera.cameras";
    /** Automatic plus the transcode profiles from Camera Settings. */
    public static final String PROFILES = "camera.profiles";

    private CameraSuggestions() {
    }
}
