/*  Copyright (C) 2016-2024 Andreas Shimokawa, Carsten Pfeiffer, Daniele
    Gobbetti, Taavi Eomäe

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.model

import android.graphics.Bitmap
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.util.RtlUtils
import nodomain.freeyourgadget.gadgetbridge.util.language.Transliterator

@Parcelize
data class MusicSpec(
    var artist: String? = null,
    var album: String? = null,
    var track: String? = null,
    var duration: Int = MUSIC_UNKNOWN,
    var trackCount: Int = MUSIC_UNKNOWN,
    var trackNr: Int = MUSIC_UNKNOWN,
    var albumArt: Bitmap? = null
) : DeviceTextAdaptable<MusicSpec>, Parcelable {

    fun copyOf(): MusicSpec = copy() // data class .copy() method is not available from Java code

    override fun transliterated(
        deviceSupport: DeviceSupport,
        transliterator: Transliterator?
    ): MusicSpec = copy(
            artist = transform(artist, deviceSupport, transliterator),
            album = transform(album, deviceSupport, transliterator),
            track = transform(track, deviceSupport, transliterator)
        )

    override fun withRtlFix(): MusicSpec {
        if (!RtlUtils.rtlSupport()) return this
        return copy(
            artist = artist?.let(RtlUtils::fixRtl),
            album = album?.let(RtlUtils::fixRtl),
            track = track?.let(RtlUtils::fixRtl)
        )
    }

    // Album art is intentionally excluded: metadata equality controls music-info updates, while
    // G-Watch tracks artwork delivery separately so a repeated bitmap does not cause extra traffic.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MusicSpec) return false
        return artist == other.artist &&
                album == other.album &&
                track == other.track &&
                duration == other.duration &&
                trackCount == other.trackCount &&
                trackNr == other.trackNr
    }

    override fun hashCode(): Int {
        var result = artist?.hashCode() ?: 0
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (track?.hashCode() ?: 0)
        result = 31 * result + duration
        result = 31 * result + trackCount
        result = 31 * result + trackNr
        return result
    }

    companion object {
        const val MUSIC_UNKNOWN: Int = -1
        const val MUSIC_UNDEFINED: Int = 0
        const val MUSIC_PLAY: Int = 1
        const val MUSIC_PAUSE: Int = 2
        const val MUSIC_PLAYPAUSE: Int = 3
        const val MUSIC_NEXT: Int = 4
        const val MUSIC_PREVIOUS: Int = 5
    }
}
