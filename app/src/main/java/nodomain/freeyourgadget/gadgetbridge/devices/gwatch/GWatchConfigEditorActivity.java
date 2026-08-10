/*  Copyright (C) 2026 Garrett Jordan

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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.gwatch.GWatchDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;

/**
 * Shows the watch's config.json in a plain text editor and writes the edited version back.
 *
 * <p>Opening the editor asks the watch for the file with a
 * {@code {"t":"config","n":"fetch"}} packet. The watch answers with ordinary
 * {@code {"t":"file"}} packets, so the reply lands on disk through the stock file receiver
 * and we read it back from {@link GWatchConstants#getDeviceStorageDir(GBDevice)}, already
 * decoded if the watch tagged it {@code d:"base64"}. That transfer has no completion
 * marker, so we reload once the per-chunk notifications stop arriving. Writing back uses
 * {@code {"t":"config","n":"post"}} - see {@link GWatchConstants} for the wire format.
 */
public class GWatchConfigEditorActivity extends AbstractGBActivity {
    private static final Logger LOG = LoggerFactory.getLogger(GWatchConfigEditorActivity.class);

    /// How long to wait for the watch to start sending the file before giving up
    private static final long REQUEST_TIMEOUT_MS = 20_000L;
    /// Quiet period after the last chunk before we treat the file as complete
    private static final long TRANSFER_SETTLE_MS = 750L;
    /// How long to wait for the watch to confirm a write. Acks are optional, so a
    /// timeout here is reported as "sent, but not confirmed" rather than as a failure.
    private static final long ACK_TIMEOUT_MS = 15_000L;

    private GBDevice device;

    private TextView statusView;
    private ProgressBar progressBar;
    private ScrollView configScroll;
    private EditText configEditor;
    private Button reloadButton;
    private Button sendButton;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /// Config exactly as it was last read from disk, or null if nothing is loaded
    private String loadedConfig = null;
    private boolean waitingForConfig = false;
    private boolean waitingForAck = false;

    private final Runnable requestTimeout = () -> {
        if (!waitingForConfig) return;
        waitingForConfig = false;
        // The watch never answered, but an earlier transfer may still be on disk
        if (loadFromDisk()) {
            setStatus(getString(R.string.gwatch_config_stale, GWatchConstants.CONFIG_FILENAME), false);
        } else {
            setStatus(getString(R.string.gwatch_config_timeout), false);
        }
        updateButtons();
    };

    /// Runs once the {"t":"file"} chunks have stopped arriving
    private final Runnable transferSettled = () -> {
        handler.removeCallbacks(requestTimeout);
        waitingForConfig = false;
        if (hasUnsavedChanges()) {
            // A chunk landed later than TRANSFER_SETTLE_MS, or the watch pushed a new
            // file on its own, and there are edits we would otherwise throw away
            setStatus(getString(R.string.gwatch_config_superseded), false);
        } else if (loadFromDisk()) {
            setStatus(getString(R.string.gwatch_config_loaded, GWatchConstants.CONFIG_FILENAME), false);
        } else {
            setStatus(getString(R.string.gwatch_config_read_failed), false);
        }
        updateButtons();
    };

    private final Runnable ackTimeout = () -> {
        if (!waitingForAck) return;
        waitingForAck = false;
        // The watch has the config, it just does not tell us how it went
        setStatus(getString(R.string.gwatch_config_sent), false);
        updateButtons();
    };

    private final BroadcastReceiver deviceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case GWatchDeviceSupport.GWATCH_FILE_WRITTEN:
                    if (!isForOurDevice(intent)) return;
                    if (!GWatchConstants.CONFIG_FILENAME.equals(
                            intent.getStringExtra(GWatchDeviceSupport.EXTRA_CONFIG_NAME))) {
                        return;
                    }
                    // One of these per chunk - reload once they stop
                    handler.removeCallbacks(transferSettled);
                    handler.postDelayed(transferSettled, TRANSFER_SETTLE_MS);
                    break;
                case GWatchDeviceSupport.GWATCH_CONFIG_ACK:
                    if (!isForOurDevice(intent)) return;
                    onConfigAck(
                            intent.getBooleanExtra(GWatchDeviceSupport.EXTRA_CONFIG_OK, true),
                            intent.getStringExtra(GWatchDeviceSupport.EXTRA_CONFIG_ERROR)
                    );
                    break;
                case GBDevice.ACTION_DEVICE_CHANGED:
                    final GBDevice changed = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
                    if (changed != null && changed.getAddress().equals(device.getAddress())) {
                        device = changed;
                        updateButtons();
                    }
                    break;
            }
        }

        private boolean isForOurDevice(final Intent intent) {
            final String address = intent.getStringExtra(GWatchDeviceSupport.EXTRA_CONFIG_ADDRESS);
            return address == null || address.equalsIgnoreCase(device.getAddress());
        }
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gwatch_config_editor);

        final Bundle bundle = getIntent().getExtras();
        device = bundle != null ? bundle.getParcelable(GBDevice.EXTRA_DEVICE) : null;
        if (device == null) {
            throw new IllegalArgumentException("Must provide a device when invoking this activity");
        }

        statusView = findViewById(R.id.gwatch_config_status);
        progressBar = findViewById(R.id.gwatch_config_progress);
        configScroll = findViewById(R.id.gwatch_config_scroll);
        configEditor = findViewById(R.id.gwatch_config_text);
        reloadButton = findViewById(R.id.gwatch_config_reload);
        sendButton = findViewById(R.id.gwatch_config_send);

        reloadButton.setOnClickListener(v -> {
            if (hasUnsavedChanges()) {
                confirmDiscard(this::requestConfig);
            } else {
                requestConfig();
            }
        });
        sendButton.setOnClickListener(v -> sendConfig());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (hasUnsavedChanges()) {
                    confirmDiscard(() -> finish());
                } else {
                    finish();
                }
            }
        });

        final IntentFilter filter = new IntentFilter();
        filter.addAction(GWatchDeviceSupport.GWATCH_FILE_WRITTEN);
        filter.addAction(GWatchDeviceSupport.GWATCH_CONFIG_ACK);
        filter.addAction(GBDevice.ACTION_DEVICE_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(deviceUpdateReceiver, filter);

        if (savedInstanceState == null) {
            requestConfig();
        } else {
            // The EditText restores its own text; recover the rest of our state
            loadedConfig = savedInstanceState.getString("loadedConfig", null);
            setStatus(loadedConfig != null
                    ? getString(R.string.gwatch_config_loaded, GWatchConstants.CONFIG_FILENAME)
                    : getString(R.string.gwatch_config_timeout), false);
            updateButtons();
        }
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("loadedConfig", loadedConfig);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(requestTimeout);
        handler.removeCallbacks(transferSettled);
        handler.removeCallbacks(ackTimeout);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(deviceUpdateReceiver);
        super.onDestroy();
    }

    /** The config.json this device last sent us, whether or not it exists yet. */
    private File getConfigFile() throws IOException {
        return new File(GWatchConstants.getDeviceStorageDir(device), GWatchConstants.CONFIG_FILENAME);
    }

    /**
     * Read config.json into the editor.
     *
     * @return true if the file was read, false if it is missing or unreadable
     */
    private boolean loadFromDisk() {
        final File file;
        try {
            file = getConfigFile();
        } catch (final IOException e) {
            LOG.error("Could not resolve the config file location", e);
            return false;
        }
        if (!file.isFile()) {
            LOG.info("No config file at {} yet", file);
            return false;
        }
        try {
            // handleFile has already base64-decoded this if the watch tagged it d:"base64",
            // so what is on disk is the real config file
            loadedConfig = new String(FileUtils.readAll(file), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            LOG.error("Could not read config file {}", file, e);
            return false;
        }
        setText(loadedConfig);
        LOG.info("Loaded {} ({} characters)", file, loadedConfig.length());
        return true;
    }

    /** Replace the editor contents, back at the top of the file. */
    private void setText(final String text) {
        configEditor.setText(text);
        // After layout, or there is nothing to scroll within yet
        configScroll.post(() -> configScroll.scrollTo(0, 0));
    }

    private void requestConfig() {
        if (!device.isConnected()) {
            // Offline, but show whatever the watch sent us last time
            if (loadFromDisk()) {
                setStatus(getString(R.string.gwatch_config_stale, GWatchConstants.CONFIG_FILENAME), false);
            } else {
                setStatus(getString(R.string.device_not_connected), false);
            }
            updateButtons();
            return;
        }

        waitingForConfig = true;
        loadedConfig = null;
        setText("");
        setStatus(getString(R.string.gwatch_config_requesting), true);
        updateButtons();

        final Intent intent = new Intent(GWatchDeviceSupport.GWATCH_CONFIG_REQUEST);
        intent.putExtra(GWatchDeviceSupport.EXTRA_CONFIG_ADDRESS, device.getAddress());
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

        handler.removeCallbacks(transferSettled);
        handler.removeCallbacks(requestTimeout);
        handler.postDelayed(requestTimeout, REQUEST_TIMEOUT_MS);
    }

    private void sendConfig() {
        if (!device.isConnected()) {
            setStatus(getString(R.string.device_not_connected), false);
            updateButtons();
            return;
        }

        final String content = configEditor.getText().toString();
        waitingForAck = true;
        setStatus(getString(R.string.gwatch_config_sending), true);
        updateButtons();

        final Intent intent = new Intent(GWatchDeviceSupport.GWATCH_CONFIG_SEND);
        intent.putExtra(GWatchDeviceSupport.EXTRA_CONFIG_ADDRESS, device.getAddress());
        intent.putExtra(GWatchDeviceSupport.EXTRA_CONFIG_DATA, content);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

        // Whatever the watch replies, this is what it now has - so stop flagging it as unsaved
        loadedConfig = content;

        handler.removeCallbacks(ackTimeout);
        handler.postDelayed(ackTimeout, ACK_TIMEOUT_MS);
    }

    private void onConfigAck(final boolean ok, final String error) {
        handler.removeCallbacks(ackTimeout);
        if (!waitingForAck) return;
        waitingForAck = false;

        if (ok) {
            setStatus(getString(R.string.gwatch_config_saved), false);
        } else if (error != null && !error.isEmpty()) {
            // Local failure - it never made it onto the wire
            setStatus(getString(R.string.gwatch_config_save_failed, error), false);
        } else {
            // n:"fail" - the watch got the file but could not parse it
            setStatus(getString(R.string.gwatch_config_parse_failed), false);
        }
        updateButtons();
    }

    private boolean hasUnsavedChanges() {
        return loadedConfig != null && !Objects.equals(loadedConfig, configEditor.getText().toString());
    }

    private void confirmDiscard(final Runnable onDiscard) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.gwatch_config_discard_title)
                .setMessage(R.string.gwatch_config_discard_message)
                .setPositiveButton(R.string.gwatch_config_discard, (dialog, which) -> onDiscard.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void setStatus(final String text, final boolean busy) {
        statusView.setText(text);
        progressBar.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
    }

    private void updateButtons() {
        final boolean busy = waitingForConfig || waitingForAck;
        final boolean connected = device.isConnected();
        reloadButton.setEnabled(!busy && connected);
        sendButton.setEnabled(!busy && connected && loadedConfig != null);
        configEditor.setEnabled(loadedConfig != null);
    }
}
