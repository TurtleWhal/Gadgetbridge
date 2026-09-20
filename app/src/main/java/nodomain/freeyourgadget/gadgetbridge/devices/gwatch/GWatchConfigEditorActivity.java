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

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
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
 *
 * <p>The config can be edited either as text or as a tree of entries. The text is the
 * authoritative copy throughout: switching to the tree parses it, and every tree edit is
 * written straight back, so sending, sharing and the unsaved-changes check only ever look
 * at the editor. A config that does not parse can only be edited as text.
 */
public class GWatchConfigEditorActivity extends AbstractGBActivity
        implements GWatchJsonTreeAdapter.Listener {
    private static final Logger LOG = LoggerFactory.getLogger(GWatchConfigEditorActivity.class);

    /// How long to wait for the watch to start sending the file before giving up
    private static final long REQUEST_TIMEOUT_MS = 20_000L;
    /// Quiet period after the last chunk before we treat the file as complete
    private static final long TRANSFER_SETTLE_MS = 750L;
    /// How long to wait for the watch to confirm a write. Acks are optional, so a
    /// timeout here is reported as "sent, but not confirmed" rather than as a failure.
    private static final long ACK_TIMEOUT_MS = 15_000L;

    /// Mime type of the share intent. Deliberately not application/json: text/plain also
    /// resolves against text-only share targets, and every target that can take a file
    /// still finds config.json in EXTRA_STREAM.
    private static final String SHARE_MIME_TYPE = "text/plain";

    /// Offered in the entry dialog, in the order the type spinner lists them
    private static final GWatchJsonNode.Type[] NODE_TYPES = GWatchJsonNode.Type.values();

    private GBDevice device;

    private TextView statusView;
    private ProgressBar progressBar;
    private ScrollView configScroll;
    private EditText configEditor;
    private Button reloadButton;
    private Button sendButton;
    private MaterialButtonToggleGroup modeGroup;
    private MaterialButton textModeButton;
    private MaterialButton treeModeButton;
    private RecyclerView treeView;
    private GWatchJsonTreeAdapter treeAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /// Config exactly as it was last read from disk, or null if nothing is loaded
    private String loadedConfig = null;
    private boolean waitingForConfig = false;
    private boolean waitingForAck = false;

    /// Parsed form of the editor contents, non-null only while the tree is on screen
    private GWatchJsonNode treeRoot = null;
    /// Which editor the user last asked for, kept across reloads and unparseable configs
    private boolean preferTree = false;
    /// Set while we move the toggle ourselves, so the listener ignores the echo
    private boolean applyingMode = false;

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
        modeGroup = findViewById(R.id.gwatch_config_mode);
        textModeButton = findViewById(R.id.gwatch_config_mode_text);
        treeModeButton = findViewById(R.id.gwatch_config_mode_tree);
        treeView = findViewById(R.id.gwatch_config_tree);

        treeAdapter = new GWatchJsonTreeAdapter(this, GWatchConstants.CONFIG_FILENAME, this);
        treeView.setLayoutManager(new LinearLayoutManager(this));
        treeView.setAdapter(treeAdapter);

        // Checked before the listener is attached, so this does not count as a switch
        modeGroup.check(R.id.gwatch_config_mode_text);
        modeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked || applyingMode) return;
            preferTree = checkedId == R.id.gwatch_config_mode_tree;
            setTreeMode(preferTree, true);
        });

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
            // The tree is rebuilt in onRestoreInstanceState, once that text is back
            preferTree = savedInstanceState.getBoolean("preferTree", false);
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
        outState.putBoolean("preferTree", preferTree);
    }

    @Override
    protected void onRestoreInstanceState(final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        // super is what puts the text back into the editor, so the tree can only be
        // rebuilt from here rather than in onCreate
        if (preferTree) {
            setTreeMode(true, false);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.menu_gwatch_config_editor, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        final MenuItem share = menu.findItem(R.id.gwatch_config_share);
        if (share != null) {
            share.setEnabled(loadedConfig != null);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == R.id.gwatch_config_share) {
            shareConfig();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
        // Whatever the tree was showing is gone; rebuild it from what just arrived
        if (preferTree && !text.isEmpty()) {
            setTreeMode(true, false);
        } else {
            applyMode(false);
        }
    }

    /**
     * Switch editors, parsing the text into a tree first when moving to tree mode.
     *
     * @param explicit true when the user asked for this, and so should be told why an
     *                 unparseable config leaves them in the text editor
     */
    private void setTreeMode(final boolean tree, final boolean explicit) {
        if (tree) {
            try {
                treeRoot = GWatchJsonNode.parse(configEditor.getText().toString());
            } catch (final JSONException e) {
                LOG.warn("Config file is not valid JSON, staying in the text editor", e);
                treeRoot = null;
                if (explicit) {
                    setStatus(getString(R.string.gwatch_json_invalid, e.getMessage()), false);
                }
                applyMode(false);
                return;
            }
            treeAdapter.setRoot(treeRoot);
        } else {
            treeRoot = null;
        }
        applyMode(tree);
    }

    /** Show one editor and hide the other, leaving the toggle on the one that won. */
    private void applyMode(final boolean tree) {
        configScroll.setVisibility(tree ? View.GONE : View.VISIBLE);
        treeView.setVisibility(tree ? View.VISIBLE : View.GONE);
        applyingMode = true;
        modeGroup.check(tree ? R.id.gwatch_config_mode_tree : R.id.gwatch_config_mode_text);
        applyingMode = false;
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

    /**
     * Hand the config currently in the editor - edits included - to another app.
     *
     * <p>The intent carries the config twice: as a config.json attachment in
     * {@link Intent#EXTRA_STREAM} and as raw JSON in {@link Intent#EXTRA_TEXT}. Which one
     * gets used is up to the receiving app - a file manager or mail client takes the
     * file, a chat or notes app pastes the text.
     */
    private void shareConfig() {
        final String content = configEditor.getText().toString();
        if (content.isEmpty()) {
            setStatus(getString(R.string.gwatch_config_share_empty), false);
            return;
        }

        final Uri uri;
        try {
            uri = writeShareCopy(content);
        } catch (final IOException e) {
            LOG.error("Could not stage the config file for sharing", e);
            setStatus(getString(R.string.gwatch_config_share_failed), false);
            return;
        }

        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(SHARE_MIME_TYPE);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_TEXT, content);
        intent.putExtra(Intent.EXTRA_TITLE, GWatchConstants.CONFIG_FILENAME);
        intent.putExtra(Intent.EXTRA_SUBJECT, GWatchConstants.CONFIG_FILENAME);
        // The chooser copies this onto the intent it hands to the picked app, which is
        // what makes our content:// uri readable over there
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(Intent.createChooser(intent, getString(R.string.gwatch_config_share)));
        } catch (final ActivityNotFoundException e) {
            LOG.error("No app to share the config file with", e);
            setStatus(getString(R.string.activity_error_share_failed), false);
        }
    }

    /**
     * Write the config to the app cache under its real name, so the receiving app sees a
     * file called config.json rather than whatever we happened to store it as.
     *
     * @return a content uri the share target can read
     */
    private Uri writeShareCopy(final String content) throws IOException {
        // shared_paths.xml exposes cache/raw through the file provider
        final File dir = new File(getCacheDir(), "raw");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create the share cache directory " + dir);
        }
        final File file = new File(dir, GWatchConstants.CONFIG_FILENAME);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return FileProvider.getUriForFile(
                this,
                getApplicationContext().getPackageName() + ".screenshot_provider",
                file
        );
    }

    @Override
    public void onNodeEdit(final GWatchJsonNode node) {
        showNodeDialog(node.getParent(), node);
    }

    @Override
    public void onNodeAdd(final GWatchJsonNode parent) {
        showNodeDialog(parent, null);
    }

    @Override
    public void onNodeDelete(final GWatchJsonNode node) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.gwatch_json_delete_title)
                .setMessage(getString(R.string.gwatch_json_delete_message, node.getDisplayName()))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    node.removeFromParent();
                    onTreeChanged();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Edit {@code node} in place, or add a new entry to {@code container} when it is null.
     *
     * <p>One dialog covers both because they ask for the same three things - a name, a
     * type, and a value - and which of them apply depends on the type rather than on
     * whether the entry already exists.
     */
    private void showNodeDialog(final GWatchJsonNode container, final GWatchJsonNode node) {
        @SuppressLint("InflateParams")
        final View view = getLayoutInflater().inflate(R.layout.dialog_gwatch_json_node, null);
        final TextInputLayout keyLayout = view.findViewById(R.id.gwatch_json_key_layout);
        final EditText keyInput = view.findViewById(R.id.gwatch_json_key_input);
        final Spinner typeSpinner = view.findViewById(R.id.gwatch_json_type_spinner);
        final TextInputLayout valueLayout = view.findViewById(R.id.gwatch_json_value_layout);
        final EditText valueInput = view.findViewById(R.id.gwatch_json_value_input);
        final SwitchCompat booleanSwitch = view.findViewById(R.id.gwatch_json_boolean_switch);

        // Only object members carry a name - array elements are addressed by position
        final boolean named = container.getType() == GWatchJsonNode.Type.OBJECT;
        keyLayout.setVisibility(named ? View.VISIBLE : View.GONE);

        final String[] typeLabels = new String[NODE_TYPES.length];
        for (int i = 0; i < NODE_TYPES.length; i++) {
            typeLabels[i] = getString(typeLabel(NODE_TYPES[i]));
        }
        final ArrayAdapter<String> typeAdapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, typeLabels);
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(typeAdapter);

        final GWatchJsonNode.Type startType = node != null ? node.getType() : GWatchJsonNode.Type.STRING;
        typeSpinner.setSelection(startType.ordinal());
        if (node != null) {
            if (named) {
                keyInput.setText(node.getKey());
            }
            if (startType == GWatchJsonNode.Type.BOOLEAN) {
                booleanSwitch.setChecked(Boolean.parseBoolean(node.getValue()));
            } else if (node.getValue() != null) {
                valueInput.setText(node.getValue());
            }
        }

        final Runnable showFieldsForType = () -> {
            final GWatchJsonNode.Type type = NODE_TYPES[typeSpinner.getSelectedItemPosition()];
            final boolean hasText = type == GWatchJsonNode.Type.STRING || type == GWatchJsonNode.Type.NUMBER;
            valueLayout.setVisibility(hasText ? View.VISIBLE : View.GONE);
            valueInput.setInputType(type == GWatchJsonNode.Type.NUMBER
                    ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            booleanSwitch.setVisibility(type == GWatchJsonNode.Type.BOOLEAN ? View.VISIBLE : View.GONE);
        };
        showFieldsForType.run();
        // Attached after the initial selection, so it only fires on real changes
        typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(final AdapterView<?> parent, final View v, final int pos, final long id) {
                showFieldsForType.run();
            }

            @Override
            public void onNothingSelected(final AdapterView<?> parent) {
            }
        });

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(node != null ? R.string.gwatch_json_edit_title : R.string.gwatch_json_add_title)
                .setView(view)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        // The click listener is replaced after the dialog is up, so rejecting what was
        // typed can leave the dialog open instead of dismissing it
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    final GWatchJsonNode.Type type = NODE_TYPES[typeSpinner.getSelectedItemPosition()];
                    final String key = keyInput.getText().toString().trim();
                    if (named) {
                        if (key.isEmpty()) {
                            keyLayout.setError(getString(R.string.gwatch_json_name_required));
                            return;
                        }
                        final GWatchJsonNode clash = container.findChild(key);
                        if (clash != null && clash != node) {
                            keyLayout.setError(getString(R.string.gwatch_json_name_duplicate));
                            return;
                        }
                        keyLayout.setError(null);
                    }

                    String value = null;
                    if (type == GWatchJsonNode.Type.NUMBER) {
                        value = valueInput.getText().toString().trim();
                        if (!GWatchJsonNode.isValidNumber(value)) {
                            valueLayout.setError(getString(R.string.gwatch_json_number_invalid));
                            return;
                        }
                        valueLayout.setError(null);
                    } else if (type == GWatchJsonNode.Type.STRING) {
                        value = valueInput.getText().toString();
                    } else if (type == GWatchJsonNode.Type.BOOLEAN) {
                        value = Boolean.toString(booleanSwitch.isChecked());
                    }

                    final String newValue = value;
                    final Runnable apply = () -> {
                        if (node == null) {
                            container.addChild(GWatchJsonNode.create(named ? key : null, type, newValue));
                        } else {
                            if (named) {
                                node.setKey(key);
                            }
                            node.setType(type, newValue);
                        }
                        onTreeChanged();
                        dialog.dismiss();
                    };

                    // Retyping empties the entry, which is worth asking about first
                    if (node != null && node.getType() != type && node.getChildCount() > 0) {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.gwatch_json_retype_title)
                                .setMessage(getString(R.string.gwatch_json_retype_message, node.getChildCount()))
                                .setPositiveButton(R.string.ok, (d, which) -> apply.run())
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    } else {
                        apply.run();
                    }
                }));
        dialog.show();
    }

    private static int typeLabel(final GWatchJsonNode.Type type) {
        switch (type) {
            case OBJECT:
                return R.string.gwatch_json_type_object;
            case ARRAY:
                return R.string.gwatch_json_type_array;
            case NUMBER:
                return R.string.gwatch_json_type_number;
            case BOOLEAN:
                return R.string.gwatch_json_type_boolean;
            case NULL:
                return R.string.gwatch_json_type_null;
            default:
                return R.string.gwatch_json_type_string;
        }
    }

    /** Redraw the tree and push the edit back into the text editor, which sends and shares. */
    private void onTreeChanged() {
        treeAdapter.refresh();
        configEditor.setText(treeRoot.toJson());
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
        // Sharing and switching editors need a loaded config, but not a connected watch
        textModeButton.setEnabled(loadedConfig != null);
        treeModeButton.setEnabled(loadedConfig != null);
        invalidateOptionsMenu();
    }
}
