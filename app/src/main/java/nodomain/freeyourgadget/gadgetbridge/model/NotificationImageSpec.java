/*  Copyright (C) 2025 Garrett Jordan

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
package nodomain.freeyourgadget.gadgetbridge.model;

/**
 * Raw image payload attached to a notification. Pixels are passed uncompressed
 * (ARGB_8888, row-major, packed) so nothing lossy or lossless re-encodes them
 * between capture and the device-side conversion to whatever the display needs.
 */
public class NotificationImageSpec {
    public int notificationId;
    /** width and height of the bitmap; argb.length must equal width * height * 4 */
    public int width;
    public int height;
    /**
     * ARGB_8888 pixel data, packed row-major (no row padding), 4 bytes per pixel
     * laid out as A, R, G, B (big-endian within each pixel — matches Android's
     * Bitmap.copyPixelsToBuffer for ARGB_8888 on little-endian platforms when
     * read as int, but stored as byte[] for Intent transport).
     */
    public byte[] argb;
}
