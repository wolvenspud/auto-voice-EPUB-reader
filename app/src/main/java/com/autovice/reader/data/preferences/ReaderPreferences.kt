package com.autovice.reader.data.preferences

data class ReaderPreferences(
    val fontSize: Float = 16f,
    val fontFamily: String = "default",
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val lineHeight: Float = 1.6f,
    /** ExoPlayer playback speed multiplier. Does NOT affect synthesis. */
    val playbackSpeed: Float = 1.0f,
    val autoScrollEnabled: Boolean = true,
    /** Seconds of scroll inactivity before auto-scroll resumes. */
    val autoScrollResumeDelaySecs: Int = 4,
    /**
     * Master switch for AI speaker attribution. When off, every line uses the narrator voice
     * (no per-character voices), regardless of whether an API key is set.
     */
    val characterAttributionEnabled: Boolean = false,
    /**
     * Rolling synthesis window size in source characters.
     * Synthesis advances until this many chars of audio are buffered ahead.
     * ~80k chars ≈ 50–60 min audio ≈ 15–20 MB Opus.
     */
    val synthesisWindowChars: Int = 80_000,
    /**
     * Characters to keep synthesised behind current position for back-seeking.
     * ~10k chars ≈ ~5 min audio.
     */
    val synthesisEvictionTrailChars: Int = 10_000,
)

enum class ReaderTheme { LIGHT, SEPIA, DARK }
