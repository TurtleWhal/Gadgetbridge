/*  Copyright (C) 2019-2024 Gordon Williams, José Rebelo

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
package nodomain.freeyourgadget.gadgetbridge.devices.gwatch;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;

public final class GWatchConstants {


    public static final UUID UUID_SERVICE_NORDIC_UART = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID UUID_CHARACTERISTIC_NORDIC_UART_TX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID UUID_CHARACTERISTIC_NORDIC_UART_RX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    // Gadgetbridge image transfer (custom, used by ESP32-S3 + LVGL firmware emulating Bangle.js).
    // Separate service so it can be enumerated/handled independently of the Nordic UART path.
    public static final UUID UUID_SERVICE_GB_IMAGE = UUID.fromString("6e500001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID UUID_CHARACTERISTIC_GB_IMAGE_DATA = UUID.fromString("6e500002-b5a3-f393-e0a9-e50e24dcca9e");

    public static final String PREF_BANGLEJS_ACTIVITY_FULL_SYNC_TRIGGER = "pref_banglejs_activity_full_sync_trigger";
    public static final String PREF_BANGLEJS_ACTIVITY_FULL_SYNC_STATUS = "pref_banglejs_activity_full_sync_status";
    public static final String PREF_BANGLEJS_ACTIVITY_FULL_SYNC_START = "pref_banglejs_activity_full_sync_start";
    public static final String PREF_BANGLEJS_ACTIVITY_FULL_SYNC_STOP = "pref_banglejs_activity_full_sync_stop";
    public static final String PREF_BANGLEJS_NOTIFICATION_MISSED_CALL_ENABLE = "pref_notification_enable_missed_call";

    /* Config file transfer - a G-Watch extension to the Bangle.js JSON protocol.
       Every packet is {"t":"config"} in both directions, with "n" naming the operation.

       Phone -> watch:

         {"t":"config","n":"fetch"}
             Send me config.json.
         {"t":"config","n":"post","m":"w","d":"..."}
         {"t":"config","n":"post","m":"a","d":"..."}
             Chunks of an edited config.json, base64 in "d". These packets carry no "c"
             at all. "m" works exactly as it does for {"t":"file"} - "w" starts the
             file, "a" appends to it.

       "d" means the same thing on every packet in both directions: base64 of the raw
       bytes. A {"t":"file"} packet carrying "d" is decoded and written as binary; the
       stock "c" field still works and is written verbatim.

       The whole file is base64-encoded first and the resulting string is what gets
       chunked, so no chunk carries interior padding and the watch can concatenate every
       "d" and decode once. Chunking the file first and encoding each piece would NOT
       work - decoded pieces only concatenate on 4-character boundaries.

       Watch -> phone:

         {"t":"config","n":"success"}
             A posted config was received and parsed. Sent once, after the last chunk.
         {"t":"config","n":"fail"}
             A posted config was received but could not be parsed.
         (silence)
             Nothing arrived. Gadgetbridge gives up on its own after a timeout.

       There is no last-chunk marker on a post, so "success" doubles as the signal that
       the watch considers the transfer complete.

       A fetch is answered with stock {"t":"file"} packets rather than {"t":"config"} -
       the watch sends the base64 of config.json the way it would send any other file.
       Gadgetbridge writes it to getDeviceStorageDir() as usual, so the stored
       config.json holds base64, and the editor decodes it on read.

       Config packets are encoded as strict JSON (see jsonToStringStrict), so a "d" value
       is always a plain quoted string - never wrapped in atob(...) or anything else. */

    /* Base64 payload field, used instead of the stock "c" on both {"t":"file"} and
       {"t":"config","n":"post"} packets. A packet carries one or the other, never both:
       "d" is decoded and written as raw bytes, "c" is written verbatim.

       Chunked base64 is decoded per packet, so every chunk except the last must be a
       multiple of 4 characters and must not carry padding - split the encoded string on
       4-character boundaries and the decoded pieces concatenate correctly. */

    /// Field carrying base64 of the raw bytes, in place of the stock "c".
    public static final String BASE64_DATA_KEY = "d";

    /// Packet type for every config operation, in both directions.
    public static final String CONFIG_PACKET = "config";
    /// Phone -> watch: send config.json back as {"t":"file"} packets.
    public static final String CONFIG_OP_FETCH = "fetch";
    /// Phone -> watch: a chunk of an edited config.json.
    public static final String CONFIG_OP_POST = "post";
    /// Watch -> phone: the posted config arrived and parsed.
    public static final String CONFIG_OP_SUCCESS = "success";
    /// Watch -> phone: the posted config arrived but could not be parsed.
    public static final String CONFIG_OP_FAIL = "fail";

    /// Name of the file the watch is expected to send, and that the editor edits.
    public static final String CONFIG_FILENAME = "config.json";
    /// Base64 characters per outgoing packet, to stay within the watch's line buffer.
    /// Every 4 of them decode to 3 bytes of config file.
    public static final int CONFIG_CHUNK_SIZE = 512;

    /**
     * The directory {@code {"t":"file"}} packets from this device are written to,
     * creating it if needed. Shared by the file receiver and the config editor so that
     * both agree on where config.json lives.
     */
    public static File getDeviceStorageDir(final GBDevice device) throws IOException {
        final File dir = new File(FileUtils.getExternalFilesDir(), FileUtils.makeValidFileName(device.getName()));
        if (!dir.isDirectory() && !dir.mkdir()) {
            throw new IOException("Cannot create device specific directory for " + device.getName());
        }
        return dir;
    }

}
