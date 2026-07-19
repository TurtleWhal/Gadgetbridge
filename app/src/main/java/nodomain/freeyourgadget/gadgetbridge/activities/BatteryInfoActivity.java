/*  Copyright (C) 2021-2024 Daniel Dakhno, José Rebelo, Petr Vaněk

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
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.app.DatePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.DatePicker;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.greenrobot.dao.query.QueryBuilder;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.entities.BatteryLevel;
import nodomain.freeyourgadget.gadgetbridge.entities.BatteryLevelDao;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryConfig;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;

public class BatteryInfoActivity extends AbstractGBActivity {
    private static final Logger LOG = LoggerFactory.getLogger(BatteryInfoActivity.class);
    GBDevice gbDevice;
    private int timeFrom;
    private int timeTo;
    private int batteryIndex = 0;
    TextView battery_status_battery_level_text;
    TextView battery_status_battery_voltage;
    LocalBroadcastManager localBroadcastManager;
    private ActivityResultLauncher<String> csvExportLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        final Context appContext = this.getApplicationContext();
        if (appContext instanceof GBApplication) {
            setContentView(R.layout.activity_battery_info);
        }

        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            gbDevice = bundle.getParcelable(GBDevice.EXTRA_DEVICE);
            batteryIndex = bundle.getInt(GBDevice.BATTERY_INDEX, 0);
        } else {
            throw new IllegalArgumentException("Must provide a device when invoking this activity");
        }

        localBroadcastManager = LocalBroadcastManager.getInstance(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(GBDevice.ACTION_DEVICE_CHANGED);
        localBroadcastManager.registerReceiver(commandReceiver, filter);

        csvExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/csv"),
                uri -> {
                    if (uri != null) {
                        new ExportCsvTask(uri).execute();
                    }
                }
        );

        final BatteryInfoChartFragment batteryInfoChartFragment = new BatteryInfoChartFragment();

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.batteryChartFragmentHolder, batteryInfoChartFragment)
                .commit();

        timeTo = (int) (System.currentTimeMillis() / 1000);

        batteryInfoChartFragment.setDateAndGetData(gbDevice, batteryIndex, timeFrom, timeTo);

        TextView battery_status_device_name_text = findViewById(R.id.battery_status_device_name);
        battery_status_battery_voltage = findViewById(R.id.battery_status_battery_voltage);
        TextView battery_status_extra_name = findViewById(R.id.battery_status_extra_name);
        final TextView battery_status_date_from_text = findViewById(R.id.battery_status_date_from_text);
        final TextView battery_status_date_to_text = findViewById(R.id.battery_status_date_to_text);
        final SeekBar battery_status_time_span_seekbar = findViewById(R.id.battery_status_time_span_seekbar);
        final TextView battery_status_time_span_text = findViewById(R.id.battery_status_time_span_text);

        LinearLayout battery_status_date_to_layout = findViewById(R.id.battery_status_date_to_layout);

        battery_status_time_span_seekbar.setMax(7);
        battery_status_time_span_seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                String text;
                switch (i) {
                    case 0:
                        text = getString(R.string.calendar_six_hours);
                        timeFrom = DateTimeUtils.shiftHours(timeTo, -6);
                        break;
                    case 1:
                        text = getString(R.string.calendar_twelve_hours);
                        timeFrom = DateTimeUtils.shiftHours(timeTo, -12);
                        break;
                    case 2:
                        text = getString(R.string.calendar_day);
                        timeFrom = DateTimeUtils.shiftDays(timeTo, -1);
                        break;
                    case 3:
                        text = getString(R.string.calendar_week);
                        timeFrom = DateTimeUtils.shiftDays(timeTo, -7);
                        break;
                    case 4:
                        text = getString(R.string.calendar_two_weeks);
                        timeFrom = DateTimeUtils.shiftDays(timeTo, -14);
                        break;
                    case 5:
                        text = getString(R.string.calendar_month);
                        timeFrom = DateTimeUtils.shiftMonths(timeTo, -1);
                        break;
                    case 6:
                        text = getString(R.string.calendar_six_months);
                        timeFrom = DateTimeUtils.shiftMonths(timeTo, -6);
                        break;
                    case 7:
                        text = getString(R.string.calendar_year);
                        timeFrom = DateTimeUtils.shiftMonths(timeTo, -12);
                        break;
                    default:
                        text = getString(R.string.calendar_day);
                        timeFrom = DateTimeUtils.shiftDays(timeTo, -1);
                }

                battery_status_time_span_text.setText(text);
                battery_status_date_from_text.setText(DateTimeUtils.formatDate(new Date(timeFrom * 1000L)));
                battery_status_date_to_text.setText(DateTimeUtils.formatDate(new Date(timeTo * 1000L)));
                batteryInfoChartFragment.setDateAndGetData(gbDevice, batteryIndex, timeFrom, timeTo);
            }


            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        //Button battery_status_calendar_button = findViewById(R.id.battery_status_calendar_button);
        battery_status_date_to_layout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                final Calendar currentDate = Calendar.getInstance();
                currentDate.setTimeInMillis(timeTo * 1000L);
                Context context = getApplicationContext();

                if (context instanceof GBApplication) {
                    new DatePickerDialog(BatteryInfoActivity.this, new DatePickerDialog.OnDateSetListener() {
                        @Override
                        public void onDateSet(DatePicker view, int year, int monthOfYear, int dayOfMonth) {

                            Calendar date = Calendar.getInstance();
                            date.set(year, monthOfYear, dayOfMonth);
                            timeTo = (int) (date.getTimeInMillis() / 1000);
                            battery_status_date_to_text.setText(DateTimeUtils.formatDate(new Date(timeTo * 1000L)));
                            battery_status_time_span_seekbar.setProgress(0);
                            battery_status_time_span_seekbar.setProgress(2);

                            batteryInfoChartFragment.setDateAndGetData(gbDevice, batteryIndex, timeFrom, timeTo);
                        }
                    }, currentDate.get(Calendar.YEAR), currentDate.get(Calendar.MONTH), currentDate.get(Calendar.DATE)).show();
                }
            }
        });


        battery_status_time_span_seekbar.setProgress(2);

        DeviceCoordinator coordinator = gbDevice.getDeviceCoordinator();

        ImageView battery_status_device_icon = findViewById(R.id.battery_status_device_icon);
        battery_status_device_icon.setImageResource(gbDevice.getDeviceCoordinator().getDefaultIconResource());
        if (gbDevice.isInitialized()) {
            battery_status_device_icon.setColorFilter(null);
        } else {
            final ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);

            battery_status_device_icon.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
        }
        battery_status_battery_level_text = findViewById(R.id.battery_status_battery_level);
        battery_status_device_name_text.setText(gbDevice.getAliasOrName());

        setBatteryLabels();
        for (BatteryConfig batteryConfig : coordinator.getBatteryConfig(gbDevice)) {
            if (batteryConfig.getBatteryIndex() == batteryIndex) {
                if (batteryConfig.getBatteryLabel() != GBDevice.BATTERY_LABEL_DEFAULT) {
                    battery_status_extra_name.setText(batteryConfig.getBatteryLabel());
                }
                if (batteryConfig.getBatteryIcon() != GBDevice.BATTERY_ICON_DEFAULT) {
                    battery_status_device_icon.setImageResource(batteryConfig.getBatteryIcon());
                    if (gbDevice.isInitialized()) {
                        battery_status_device_icon.setColorFilter(this.getResources().getColor(R.color.accent));
                    }
                }
            }
        }
    }

    private void setBatteryLabels() {
        String level = gbDevice.getBatteryLevel(batteryIndex) > 0 ? String.format("%1s%%", gbDevice.getBatteryLevel(batteryIndex)) : "";
        String voltage = gbDevice.getBatteryVoltage(batteryIndex) > 0 ? String.format("%1sV", gbDevice.getBatteryVoltage(batteryIndex)) : "";
        battery_status_battery_level_text.setText(level);
        battery_status_battery_voltage.setText(voltage);
    }

    BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            LOG.debug("device receiver received {}", intent.getAction());
            if (GBDevice.ACTION_DEVICE_CHANGED.equals(intent.getAction())) {
                GBDevice newDevice = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
                if (gbDevice.equals(newDevice)) {
                    gbDevice = newDevice;
                    setBatteryLabels();
                }

            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(commandReceiver);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_battery_info, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.battery_info_export_csv) {
            final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
            final String safeName = gbDevice.getAliasOrName().replaceAll("[^A-Za-z0-9._-]", "_");
            final String filename = String.format(Locale.ROOT, "battery_%s_b%d_%s_to_%s.csv",
                    safeName,
                    batteryIndex,
                    sdf.format(new Date(timeFrom * 1000L)),
                    sdf.format(new Date(timeTo * 1000L)));
            csvExportLauncher.launch(filename);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class ExportCsvTask extends AsyncTask<Void, Void, Integer> {
        private final Uri uri;

        ExportCsvTask(Uri uri) {
            this.uri = uri;
        }

        @Override
        protected Integer doInBackground(Void... voids) {
            try (DBHandler dbHandler = GBApplication.acquireDB()) {
                final Device dbDevice = DBHelper.findDevice(gbDevice, dbHandler.getDaoSession());
                if (dbDevice == null) {
                    return -1;
                }
                final BatteryLevelDao dao = dbHandler.getDaoSession().getBatteryLevelDao();
                final QueryBuilder<BatteryLevel> qb = dao.queryBuilder();
                qb.where(BatteryLevelDao.Properties.DeviceId.eq(dbDevice.getId()))
                        .where(BatteryLevelDao.Properties.BatteryIndex.eq(batteryIndex))
                        .where(BatteryLevelDao.Properties.Timestamp.gt(timeFrom))
                        .where(BatteryLevelDao.Properties.Timestamp.lt(timeTo))
                        .orderAsc(BatteryLevelDao.Properties.Timestamp);
                final List<BatteryLevel> samples = qb.build().list();

                final OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) {
                    return -1;
                }
                final SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT);
                final BatteryState[] states = BatteryState.values();
                try (Writer w = new OutputStreamWriter(out)) {
                    w.write("timestamp_unix,timestamp_iso,level_percent,voltage,charging,battery_state\n");
                    for (BatteryLevel s : samples) {
                        final int stateOrdinal = s.getBatteryState();
                        final BatteryState state = (stateOrdinal >= 0 && stateOrdinal < states.length)
                                ? states[stateOrdinal] : BatteryState.UNKNOWN;
                        final boolean charging = state == BatteryState.BATTERY_CHARGING
                                || state == BatteryState.BATTERY_CHARGING_FULL;
                        w.write(String.format(Locale.ROOT, "%d,%s,%d,%s,%s,%s\n",
                                s.getTimestamp(),
                                iso.format(new Date(s.getTimestamp() * 1000L)),
                                s.getLevel(),
                                Float.isNaN(s.getVoltage()) ? "" : String.valueOf(s.getVoltage()),
                                charging ? "true" : "false",
                                state.name()));
                    }
                }
                return samples.size();
            } catch (Exception e) {
                LOG.error("Failed to export battery CSV", e);
                return -1;
            }
        }

        @Override
        protected void onPostExecute(Integer count) {
            if (count == null || count < 0) {
                Toast.makeText(BatteryInfoActivity.this, R.string.battery_info_export_csv_failed, Toast.LENGTH_LONG).show();
            } else if (count == 0) {
                Toast.makeText(BatteryInfoActivity.this, R.string.battery_info_export_csv_empty, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(BatteryInfoActivity.this, R.string.battery_info_export_csv_success, Toast.LENGTH_SHORT).show();
            }
        }
    }

}


