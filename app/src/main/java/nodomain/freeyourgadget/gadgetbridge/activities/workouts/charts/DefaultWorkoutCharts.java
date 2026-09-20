/*  Copyright (C) 2025-2026 José Rebelo, a0z, Me7c7, punchdeerflyscorpion, Thomas Kuehne

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
package nodomain.freeyourgadget.gadgetbridge.activities.workouts.charts;

import static nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutValueFormatter.getUnitString;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_BPM;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_BREATHS_PER_MIN;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_MILLISECONDS;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_MM;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_PERCENTAGE;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_SECONDS_PER_100_METERS;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_SECONDS_PER_500_METERS;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_SECONDS_PER_KM;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_SPM;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_WATT;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.ScatterChart;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.data.ScatterData;
import com.github.mikephil.charting.data.ScatterDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.DecimalValueFormatter;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.SpeedYLabelFormatter;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.TimestampTranslation;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;
import nodomain.freeyourgadget.gadgetbridge.model.workout.WorkoutChart;
import nodomain.freeyourgadget.gadgetbridge.util.Accumulator;

public class DefaultWorkoutCharts {
    public static List<WorkoutChart> buildDefaultCharts(final Context context,
                                                        final List<? extends ActivityPoint> activityPoints,
                                                        final ActivityKind activityKind) {
        final ActivityKind.CycleUnit cycleUnit = ActivityKind.getCycleUnit(activityKind);
        final List<WorkoutChart> charts = new LinkedList<>();
        final TimestampTranslation tsTranslation = new TimestampTranslation();
        final int initialCapacity = activityPoints.size();
        final List<Entry> heartRateDataPoints = new ArrayList<>(initialCapacity);
        final List<Entry> speedDataPoints = new ArrayList<>(initialCapacity);
        final List<Entry> cadenceDataPoints = new ArrayList<>(initialCapacity);
        final List<Entry> elevationDataPoints = new ArrayList<>(initialCapacity);
        final List<Entry> powerDataPoints = new ArrayList<>(initialCapacity);
        final List<Entry> respiratoryRatePoints = new ArrayList<>(initialCapacity);
        final List<Entry> temperatureDataPoints = new ArrayList<>(initialCapacity);
        final List<Entry> depthDataPoints = new ArrayList<>(initialCapacity);
        final List<Entry> distancePoints = new ArrayList<>(initialCapacity);
        final List<Entry> staminaPoints = new ArrayList<>(initialCapacity);
        final List<Entry> bodyEnergyPoints = new ArrayList<>(initialCapacity);
        final List<Entry> stepLengthPoints = new ArrayList<>(initialCapacity);
        final List<Entry> n2LoadPoints = new ArrayList<>(initialCapacity);
        final List<Entry> cnsToxicityPoints = new ArrayList<>(initialCapacity);
        final List<Entry> verticalOscillationPoints = new ArrayList<>(initialCapacity);
        final List<Entry> stanceTimePercentPoints = new ArrayList<>(initialCapacity);
        final List<Entry> stanceTimePoints = new ArrayList<>(initialCapacity);
        final List<Entry> verticalRatioPoints = new ArrayList<>(initialCapacity);
        final List<Entry> stanceTimeBalancePoints = new ArrayList<>(initialCapacity);
        final List<Entry> performanceConditionPoints = new ArrayList<>(initialCapacity);

        // some activities / devices provide all points with zero values
        boolean hasSpeedValues = false;
        boolean hasCadenceValues = false;
        boolean hasElevationValues = false;
        boolean hasPowerValues = false;
        boolean hasRespiratoryRateValues = false;
        boolean hasTemperatureValues = false;
        boolean hasDepthValues = false;
        boolean hasDistanceValues = false;
        boolean hasBodyEnergyValues = false;
        boolean hasStaminaValues = false;
        boolean hasStepLengthValues = false;
        boolean hasN2LoadValues = false;
        boolean hasCnsToxicityValues = false;
        boolean hasVerticalOscillationValues = false;
        boolean hasStanceTimePercentValues = false;
        boolean hasStanceTimeValues = false;
        boolean hasVerticalRatioValues = false;
        boolean hasStanceTimeBalanceValues = false;
        boolean hasPerformanceConditionValues = false;

        final Accumulator cadenceAccumulator = new Accumulator();
        final Accumulator temperatureAccumulator = new Accumulator();

        // Distance is not carried by every source (Xiaomi/Bangle GPS tracks expose position but no
        // distance field). When no point provides one, derive a running distance from the GPS fixes
        // so the distance chart still renders. Null for sources that already carry native distance.
        final double[] derivedDistances = deriveCumulativeDistances(activityPoints);

        for (int i = 0; i <= activityPoints.size() - 1; i++) {
            final ActivityPoint point = activityPoints.get(i);
            final long tsShorten = tsTranslation.shorten((int) point.getTime().getTime());

            // HR
            final int heartRate = point.getHeartRate();
            if (heartRate > 0) {
                heartRateDataPoints.add(new Entry(tsShorten, heartRate));
            }

            // Elevation
            final double elevation = point.getAltitude();
            if (elevation > GPSCoordinate.UNKNOWN_ALTITUDE) {
                elevationDataPoints.add(new Entry(tsShorten, (float) elevation));
                hasElevationValues = hasElevationValues || (elevation != 0.0);
            }

            // Speed
            final float speed = point.getSpeed();
            if (speed >= 0.0f) {
                speedDataPoints.add(new Entry(tsShorten, speed));
                hasSpeedValues = hasSpeedValues || (speed > 0.0f);
            }

            // Cadence
            final float cadence = point.getCadence();
            if (cadence >= 0.0f) {
                cadenceDataPoints.add(new Entry(tsShorten, cadence));
                cadenceAccumulator.add(cadence);
                hasCadenceValues = hasCadenceValues || (cadence > 0.0f);
            }

            final float power = point.getPower();
            if (power >= 0.0f) {
                powerDataPoints.add(new Entry(tsShorten, power));
                hasPowerValues = hasPowerValues || (power > 0.0f);
            }

            final float respiratoryRate = point.getRespiratoryRate();
            if (respiratoryRate >= 0.0f) {
                respiratoryRatePoints.add(new Entry(tsShorten, respiratoryRate));
                hasRespiratoryRateValues = hasRespiratoryRateValues || (respiratoryRate > 0.0f);
            }

            // Depth (diving activity)
            final double depth = point.getDepth();
            if (depth >= 0.0) {
                depthDataPoints.add(new Entry(tsShorten, (float) -depth));
                hasDepthValues = hasDepthValues || (depth > 0.0);
            }

            // Temperature
            final double temperature = point.getTemperature();
            if (temperature > -273) {
                temperatureDataPoints.add(new Entry(tsShorten, (float) temperature));
                temperatureAccumulator.add(temperature);
                hasTemperatureValues = hasTemperatureValues || (temperature != 0.0);
            }

            // Distance
            double distance = point.getDistance();
            if (distance < 0.0 && derivedDistances != null) {
                distance = derivedDistances[i];
            }
            if (distance >= 0.0) {
                distancePoints.add(new Entry(tsShorten, (float) distance));
                hasDistanceValues = hasDistanceValues || (distance > 0);
            }

            // Body Energy
            final float bodyEnergy = point.getBodyEnergy();
            if (bodyEnergy >= 0.0f) {
                bodyEnergyPoints.add(new Entry(tsShorten, bodyEnergy));
                hasBodyEnergyValues = hasBodyEnergyValues || (bodyEnergy > 0.0f);
            }

            // Stamina
            final float stamina = point.getStamina();
            if (stamina >= 0.0f) {
                staminaPoints.add(new Entry(tsShorten, stamina));
                hasStaminaValues = hasStaminaValues || (stamina > 0.0f);
            }

            // Step Length
            final int stepLength = point.getStepLength();
            if (stepLength >= 0) {
                stepLengthPoints.add(new Entry(tsShorten, stepLength));
                hasStepLengthValues = hasStepLengthValues || (stepLength > 0);
            }

            // CNS Toxicity
            final float cnsToxicity = point.getCnsToxicity();
            if (cnsToxicity >= 0.0f) {
                cnsToxicityPoints.add(new Entry(tsShorten, cnsToxicity));
                hasCnsToxicityValues = hasCnsToxicityValues || (cnsToxicity > 0.0f);
            }

            // N2 Load
            final float n2Load = point.getN2Load();
            if (n2Load >= 0.0f) {
                n2LoadPoints.add(new Entry(tsShorten, n2Load));
                hasN2LoadValues = hasN2LoadValues || (n2Load > 0.0f);
            }

            // Running dynamics
            final float verticalOscillation = point.getVerticalOscillation();
            if (!Float.isNaN(verticalOscillation)) {
                verticalOscillationPoints.add(new Entry(tsShorten, verticalOscillation));
                hasVerticalOscillationValues = hasVerticalOscillationValues || (verticalOscillation > 0.0f);
            }

            final float stanceTimePercent = point.getStanceTimePercent();
            if (!Float.isNaN(stanceTimePercent)) {
                stanceTimePercentPoints.add(new Entry(tsShorten, stanceTimePercent));
                hasStanceTimePercentValues = hasStanceTimePercentValues || (stanceTimePercent > 0.0f);
            }

            final float stanceTime = point.getStanceTime();
            if (!Float.isNaN(stanceTime)) {
                stanceTimePoints.add(new Entry(tsShorten, stanceTime));
                hasStanceTimeValues = hasStanceTimeValues || (stanceTime > 0.0f);
            }

            final float verticalRatio = point.getVerticalRatio();
            if (!Float.isNaN(verticalRatio)) {
                verticalRatioPoints.add(new Entry(tsShorten, verticalRatio));
                hasVerticalRatioValues = hasVerticalRatioValues || (verticalRatio > 0.0f);
            }

            final float stanceTimeBalance = point.getStanceTimeBalance();
            if (!Float.isNaN(stanceTimeBalance)) {
                stanceTimeBalancePoints.add(new Entry(tsShorten, stanceTimeBalance));
                hasStanceTimeBalanceValues = hasStanceTimeBalanceValues || (stanceTimeBalance > 0.0f);
            }

            final int performanceCondition = point.getPerformanceCondition();
            if (performanceCondition > Integer.MIN_VALUE) {
                // Integer.MIN_VALUE is the no-data sentinel; any other value is a valid
                // reading, including negative ones (performance condition can be < 0).
                performanceConditionPoints.add(new Entry(tsShorten, performanceCondition));
                hasPerformanceConditionValues = true;
            }
        }

        if (!heartRateDataPoints.isEmpty()) {
            charts.add(createHeartRateChart(context, heartRateDataPoints));
        }

        if (hasSpeedValues && !speedDataPoints.isEmpty()) {
            charts.add(createSpeedChart(context, activityKind, speedDataPoints));
        }

        if (hasCadenceValues && !cadenceDataPoints.isEmpty()) {
            charts.add(createCadenceChart(context, cycleUnit, cadenceDataPoints, cadenceAccumulator));
        }

        if (hasElevationValues && !elevationDataPoints.isEmpty()) {
            charts.add(createElevationChart(context, elevationDataPoints));
        }

        if (hasPowerValues && !powerDataPoints.isEmpty()) {
            charts.add(createPowerChart(context, powerDataPoints));
        }

        if (hasRespiratoryRateValues && !respiratoryRatePoints.isEmpty()) {
            charts.add(createRespiratoryRateChart(context, respiratoryRatePoints));
        }

        if (hasDepthValues && !depthDataPoints.isEmpty()) {
            charts.add(createDepthChart(context, depthDataPoints));
        }

        if (hasTemperatureValues && !temperatureDataPoints.isEmpty()) {
            charts.add(createTemperatureChart(context, temperatureDataPoints, temperatureAccumulator));
        }

        if (hasDistanceValues && !distancePoints.isEmpty()) {
            charts.add(createDistanceChart(context, distancePoints));
        }

        if (hasBodyEnergyValues && !bodyEnergyPoints.isEmpty()) {
            charts.add(createBodyEnergyChart(context, bodyEnergyPoints));
        }

        if (hasStaminaValues && !staminaPoints.isEmpty()) {
            charts.add(createStaminaChart(context, staminaPoints));
        }

        if (hasStepLengthValues && !stepLengthPoints.isEmpty()) {
            charts.add(createStepLengthChart(context, stepLengthPoints));
        }

        if (hasCnsToxicityValues && !cnsToxicityPoints.isEmpty()) {
            charts.add(createCnsToxicityChart(context, cnsToxicityPoints));
        }

        if (hasN2LoadValues && !n2LoadPoints.isEmpty()) {
            charts.add(createN2LoadChart(context, n2LoadPoints));
        }

        if (hasVerticalOscillationValues && !verticalOscillationPoints.isEmpty()) {
            charts.add(createVerticalOscillationChart(context, verticalOscillationPoints));
        }

        if (hasStanceTimePercentValues && !stanceTimePercentPoints.isEmpty()) {
            charts.add(createStanceTimePercentChart(context, stanceTimePercentPoints));
        }

        if (hasStanceTimeValues && !stanceTimePoints.isEmpty()) {
            charts.add(createStanceTimeChart(context, stanceTimePoints));
        }

        if (hasVerticalRatioValues && !verticalRatioPoints.isEmpty()) {
            charts.add(createVerticalRatioChart(context, verticalRatioPoints));
        }

        if (hasStanceTimeBalanceValues && !stanceTimeBalancePoints.isEmpty()) {
            charts.add(createStanceTimeBalanceChart(context, stanceTimeBalancePoints));
        }

        if (hasPerformanceConditionValues && !performanceConditionPoints.isEmpty()) {
            charts.add(createPerformanceConditionChart(context, performanceConditionPoints));
        }

        return charts;
    }

    /**
     * Derive a per-point cumulative distance (metres) when the source carries no distance of its own,
     * so the distance chart still renders. Returns {@code null} when any point already provides a
     * distance (honour the device value) or when there is nothing to derive from, so the caller falls
     * back to {@link ActivityPoint#getDistance()}.
     * <p>
     * Two sources, in order of trust: GPS fixes (outdoor tracks that expose position but no distance,
     * e.g. Xiaomi/Bangle), else the speed stream integrated over time (indoor treadmill/bike workouts
     * that carry machine-reported speed but neither location nor distance). Entries with no usable
     * value are {@link Double#NaN} so they contribute no distance and inject no spurious hop.
     */
    static double[] deriveCumulativeDistances(final List<? extends ActivityPoint> points) {
        for (final ActivityPoint p : points) {
            if (p.getDistance() >= 0.0) {
                return null; // source already carries distance — do not override it
            }
        }
        final double[] fromGps = deriveFromGps(points);
        if (fromGps != null) {
            return fromGps;
        }
        return deriveFromSpeed(points);
    }

    /**
     * Cumulative distance from consecutive GPS fixes via the pure-Java
     * {@link GPSCoordinate#distanceHaversine} (off {@code android.location.Location}, unit-testable).
     * Null Island (0,0) no-fix placeholders and locationless points are skipped ({@code NaN}), so a
     * momentary dropout never injects a jump. {@code null} if there is no usable fix.
     */
    private static double[] deriveFromGps(final List<? extends ActivityPoint> points) {
        final double[] distances = new double[points.size()];
        double cumulativeMeters = 0.0;
        GPSCoordinate previous = null;
        boolean anyFix = false;
        for (int i = 0; i < points.size(); i++) {
            final GPSCoordinate location = points.get(i).getLocation();
            if (location == null || isNullIsland(location)) {
                distances[i] = Double.NaN;
                continue;
            }
            if (previous != null) {
                cumulativeMeters += GPSCoordinate.distanceHaversine(previous, location);
            }
            distances[i] = cumulativeMeters;
            previous = location;
            anyFix = true;
        }
        return anyFix ? distances : null;
    }

    /**
     * Cumulative distance from the speed stream, integrated over time (trapezoid rule between
     * consecutive samples). For indoor workouts whose gadget was connected to the machine and thus
     * report speed but no position. Integrating a measured speed is well-behaved (a smoothing, unlike
     * differentiating position into speed); it never fabricates from nothing — no speed, no distance.
     * Points without a speed value are {@code NaN}. {@code null} if no point carries a speed.
     */
    private static double[] deriveFromSpeed(final List<? extends ActivityPoint> points) {
        final double[] distances = new double[points.size()];
        double cumulativeMeters = 0.0;
        float previousSpeed = -1.0f; // m/s; <0 = no previous sample to bridge from
        long previousTsMillis = -1L;
        boolean anySpeed = false;
        for (int i = 0; i < points.size(); i++) {
            final ActivityPoint point = points.get(i);
            final float speed = point.getSpeed(); // m/s, <0 when absent
            if (speed < 0.0f) {
                distances[i] = Double.NaN;
                continue;
            }
            final long tsMillis = point.getTime().getTime();
            if (previousSpeed >= 0.0f && previousTsMillis >= 0L) {
                final double dtSeconds = (tsMillis - previousTsMillis) / 1000.0;
                if (dtSeconds > 0.0) {
                    cumulativeMeters += 0.5 * (previousSpeed + speed) * dtSeconds;
                }
            }
            distances[i] = cumulativeMeters;
            previousSpeed = speed;
            previousTsMillis = tsMillis;
            anySpeed = anySpeed || speed > 0.0f;
        }
        return anySpeed ? distances : null;
    }

    private static boolean isNullIsland(final GPSCoordinate location) {
        return location.getLatitude() == 0.0 && location.getLongitude() == 0.0;
    }

    private static WorkoutChart createElevationChart(final Context context,
                                                     final List<Entry> elevationDataPoints) {
        final WorkoutChartUnits units = chartUnits();
        final WorkoutChartUnits.Quantity quantity = WorkoutChartUnits.Quantity.ELEVATION;
        final String unitString = getUnitString(context, units.token(quantity));
        final String label = String.format("%s (%s)", context.getString(R.string.Elevation), unitString);
        // Elevation is safe (and often desirable) to interpolate across a gap: altitude
        // typically doesn't jump discontinuously, even across a paused or dropped-out stretch.
        final LineData lineData = createLineData(context, convertPoints(elevationDataPoints, units, quantity), label, ContextCompat.getColor(context, R.color.chart_line_elevation));
        return new WorkoutChart(
                "elevation",
                context.getString(R.string.Elevation),
                ActivitySummaryEntries.GROUP_ELEVATION,
                lineData,
                new DecimalValueFormatter(units.decimals(quantity)),
                unitString
        );
    }

    private static WorkoutChart createHeartRateChart(final Context context,
                                                     final List<Entry> heartRateDataPoints) {
        final String label = String.format("%s(%s)", context.getString(R.string.heart_rate), getUnitString(context, UNIT_BPM));
        final LineData lineData = createGappedLineData(context, heartRateDataPoints, label, ContextCompat.getColor(context, R.color.chart_line_heart_rate));
        final ValueFormatter integerFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        };
        return new WorkoutChart(
                "heart_rate",
                context.getString(R.string.heart_rate),
                ActivitySummaryEntries.GROUP_HEART_RATE,
                lineData,
                integerFormatter,
                getUnitString(context, UNIT_BPM)
        );
    }

    private static WorkoutChart createSpeedChart(final Context context,
                                                 final ActivityKind activityKind,
                                                 final List<Entry> speedDataPoints) {
        final WorkoutChartUnits units = chartUnits(activityKind);
        if (ActivityKind.isRowingActivity(activityKind)) {
            final String unitString = getUnitString(context, units.token(WorkoutChartUnits.Quantity.PACE_500M));
            final String label = String.format("%s (%s)", context.getString(R.string.Pace), unitString);
            final LineData lineData = createGappedLineData(context, speedDataPoints, label, ContextCompat.getColor(context, R.color.chart_line_speed));
            return new WorkoutChart(
                    "pace",
                    context.getString(R.string.Pace),
                    ActivitySummaryEntries.GROUP_SPEED,
                    lineData,
                    new SpeedYLabelFormatter(UNIT_SECONDS_PER_500_METERS),
                    unitString
            );
        } else if (ActivityKind.isSwimActivity(activityKind)) {
            final String unitString = getUnitString(context, units.token(WorkoutChartUnits.Quantity.PACE_SWIM));
            final String label = String.format("%s (%s)", context.getString(R.string.Pace), unitString);
            final LineData lineData = createGappedLineData(context, speedDataPoints, label, ContextCompat.getColor(context, R.color.chart_line_speed));
            return new WorkoutChart(
                    "pace",
                    context.getString(R.string.Pace),
                    ActivitySummaryEntries.GROUP_SPEED,
                    lineData,
                    new SpeedYLabelFormatter(UNIT_SECONDS_PER_100_METERS),
                    unitString
            );
        } else if (ActivityKind.isPaceActivity(activityKind)) {
            final String unitString = getUnitString(context, units.token(WorkoutChartUnits.Quantity.PACE));
            final String label = String.format("%s (%s)", context.getString(R.string.Pace), unitString);
            final LineData lineData = createGappedLineData(context, speedDataPoints, label, ContextCompat.getColor(context, R.color.chart_line_speed));
            return new WorkoutChart(
                    "pace",
                    context.getString(R.string.Pace),
                    ActivitySummaryEntries.GROUP_SPEED,
                    lineData,
                    new SpeedYLabelFormatter(UNIT_SECONDS_PER_KM),
                    unitString
            );
        } else {
            final WorkoutChartUnits.Quantity quantity = WorkoutChartUnits.Quantity.SPEED;
            final String unitString = getUnitString(context, units.token(quantity));
            final String label = String.format("%s (%s)", context.getString(R.string.Speed), unitString);
            final LineData lineData = createGappedLineData(context, convertPoints(speedDataPoints, units, quantity), label, ContextCompat.getColor(context, R.color.chart_line_speed));
            return new WorkoutChart(
                    "speed",
                    context.getString(R.string.Speed),
                    ActivitySummaryEntries.GROUP_SPEED,
                    lineData,
                    new DecimalValueFormatter(units.decimals(quantity)),
                    unitString
            );
        }
    }

    private static WorkoutChart createCadenceChart(final Context context,
                                                   final ActivityKind.CycleUnit cycleUnit,
                                                   final List<Entry> cadenceDataPoints,
                                                   final Accumulator cadenceAccumulator) {
        final String label = String.format("%s (%s)", context.getString(R.string.workout_cadence), getUnitString(context, getCadenceUnit(cycleUnit)));
        final ScatterDataSet dataset = createScatterDataSet(context, cadenceDataPoints, label, ContextCompat.getColor(context, R.color.chart_cadence_circle));
        final ValueFormatter integerFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        };
        float xAxisMaximum = Math.max(
                (float) (cadenceAccumulator.getMax() + 30),
                (float) cadenceAccumulator.getAverage() * 2
        );

        return new WorkoutChart(
                "cadence",
                context.getString(R.string.workout_cadence),
                ActivitySummaryEntries.GROUP_CADENCE,
                new ScatterData(dataset),
                integerFormatter,
                getUnitString(context, UNIT_SPM),
                lineChart -> {
                    YAxis yAxisLeft = lineChart.getAxisLeft();
                    yAxisLeft.setAxisMinimum(0);
                    yAxisLeft.setAxisMaximum(xAxisMaximum);
                    YAxis yAxisRight = lineChart.getAxisRight();
                    yAxisRight.setAxisMinimum(0);
                    yAxisRight.setAxisMaximum(xAxisMaximum);
                    return kotlin.Unit.INSTANCE;
                }
        );
    }

    private static WorkoutChart createPowerChart(final Context context,
                                                 final List<Entry> powerDataPoints) {
        final String label = String.format("%s (%s)", context.getString(R.string.workout_power), getUnitString(context, UNIT_WATT));
        final LineData lineData = createGappedLineData(context, powerDataPoints, label, ContextCompat.getColor(context, R.color.chart_line_power));
        final ValueFormatter integerFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        };
        return new WorkoutChart("power", context.getString(R.string.workout_power), ActivitySummaryEntries.GROUP_POWER, lineData, integerFormatter, getUnitString(context, UNIT_WATT));
    }

    private static WorkoutChart createRespiratoryRateChart(final Context context,
                                                           final List<Entry> powerDataPoints) {
        final String label = String.format("%s (%s)", context.getString(R.string.respiratoryrate), getUnitString(context, UNIT_BREATHS_PER_MIN));
        final LineData lineData = createGappedLineData(context, powerDataPoints, label, ContextCompat.getColor(context, R.color.respiratory_rate_color));
        final ValueFormatter integerFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        };
        return new WorkoutChart(
                "respiratory_rate",
                context.getString(R.string.respiratoryrate),
                ActivitySummaryEntries.GROUP_RESPIRATORY_RATE,
                lineData,
                integerFormatter,
                getUnitString(context, UNIT_BREATHS_PER_MIN)
        );
    }

    private static WorkoutChart createDepthChart(final Context context,
                                                 final List<Entry> depthDataPoints) {
        final WorkoutChartUnits units = chartUnits();
        final WorkoutChartUnits.Quantity quantity = WorkoutChartUnits.Quantity.DEPTH;
        final String unitString = getUnitString(context, units.token(quantity));
        final String label = String.format("%s(%s)", context.getString(R.string.diving_depth), unitString);
        // Depth changes continuously during a dive, so it's safe to interpolate across a gap
        // rather than break the segment.
        final LineData lineData = createLineData(context, convertPoints(depthDataPoints, units, quantity), label, ContextCompat.getColor(context, R.color.chart_line_depth));
        return new WorkoutChart(
                "diving_depth",
                context.getString(R.string.diving_depth),
                ActivitySummaryEntries.GROUP_DIVING,
                lineData,
                new DecimalValueFormatter(units.decimals(quantity)),
                unitString
        );
    }

    private static WorkoutChart createTemperatureChart(final Context context,
                                                       final List<Entry> temperatureDataPoints,
                                                       final Accumulator temperatureAccumulator) {
        final WorkoutChartUnits units = chartUnits();
        final WorkoutChartUnits.Quantity quantity = WorkoutChartUnits.Quantity.TEMPERATURE;
        final String unitString = getUnitString(context, units.token(quantity));
        final String label = String.format("%s(%s)", context.getString(R.string.menuitem_temperature), unitString);
        // Ambient temperature drifts slowly and gradually, so it's safe to interpolate across
        // a gap rather than break the segment.
        final LineData lineData = createLineData(context, convertPoints(temperatureDataPoints, units, quantity), label, ContextCompat.getColor(context, R.color.chart_line_heart_rate));
        // axis bounds mirror the plotted (already converted) values
        final float axisMin = (float) units.convert(quantity, 0);
        final float axisMax = (float) units.convert(quantity, Math.max(35, temperatureAccumulator.getMax() + 5));
        return new WorkoutChart(
                "temperature",
                context.getString(R.string.menuitem_temperature),
                ActivitySummaryEntries.GROUP_TEMPERATURE,
                lineData,
                new DecimalValueFormatter(units.decimals(quantity)),
                unitString,
                lineChart -> {
                    YAxis yAxisLeft = lineChart.getAxisLeft();
                    yAxisLeft.setAxisMinimum(axisMin);
                    yAxisLeft.setAxisMaximum(axisMax);
                    YAxis yAxisRight = lineChart.getAxisRight();
                    yAxisRight.setAxisMinimum(axisMin);
                    yAxisRight.setAxisMaximum(axisMax);
                    return kotlin.Unit.INSTANCE;
                }
        );
    }

    private static WorkoutChart createDistanceChart(final Context context,
                                                    final List<Entry> distancePoints) {
        final WorkoutChartUnits units = chartUnits();
        final WorkoutChartUnits.Quantity quantity = WorkoutChartUnits.Quantity.DISTANCE;
        final String unitString = getUnitString(context, units.token(quantity));
        final String label = String.format("%s(%s)", context.getString(R.string.distance), unitString);
        final LineData lineData = createGappedLineData(context, convertPoints(distancePoints, units, quantity), label, ContextCompat.getColor(context, R.color.chart_line_distance));
        return new WorkoutChart(
                "chart_distance",
                context.getString(R.string.distance),
                ActivitySummaryEntries.GROUP_DISTANCE,
                lineData,
                new DecimalValueFormatter(units.decimals(quantity)),
                unitString
        );
    }

    private static WorkoutChart createBodyEnergyChart(final Context context,
                                                      final List<Entry> bodyEnergyPoints) {
        final String label = String.format("%s(%s)", context.getString(R.string.body_energy), getUnitString(context, UNIT_PERCENTAGE));
        // Body energy is a slowly-changing reserve metric, so it's safe to interpolate across
        // a gap rather than break the segment.
        final LineData lineData = createLineData(context, bodyEnergyPoints, label, ContextCompat.getColor(context, R.color.chart_line_body_energy));
        final ValueFormatter valueFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        };
        return new WorkoutChart(
                "chart_body_energy",
                context.getString(R.string.body_energy),
                ActivitySummaryEntries.GROUP_TRAINING_EFFECT,
                lineData,
                valueFormatter,
                getUnitString(context, UNIT_PERCENTAGE)
        );
    }

    private static WorkoutChart createStaminaChart(final Context context,
                                                   final List<Entry> staminaPoints) {
        final String label = String.format("%s(%s)", context.getString(R.string.stamina), getUnitString(context, UNIT_PERCENTAGE));
        // Stamina is a slowly draining/recovering reserve, so it's safe to interpolate across
        // a gap rather than break the segment.
        final LineData lineData = createLineData(context, staminaPoints, label, ContextCompat.getColor(context, R.color.chart_line_stamina));
        final ValueFormatter valueFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        };
        return new WorkoutChart(
                "chart_stamina",
                context.getString(R.string.stamina),
                ActivitySummaryEntries.GROUP_TRAINING_EFFECT,
                lineData,
                valueFormatter,
                getUnitString(context, UNIT_PERCENTAGE)
        );
    }

    private static WorkoutChart createStepLengthChart(final Context context,
                                                      final List<Entry> stepLengthPoints) {
        final WorkoutChartUnits units = chartUnits();
        final WorkoutChartUnits.Quantity quantity = WorkoutChartUnits.Quantity.STEP_LENGTH;
        final String unitString = getUnitString(context, units.token(quantity));
        final String label = String.format("%s(%s)", context.getString(R.string.step_length), unitString);
        final LineData lineData = createGappedLineData(context, convertPoints(stepLengthPoints, units, quantity), label, ContextCompat.getColor(context, R.color.chart_line_step_length));
        return new WorkoutChart(
                "chart_step_length",
                context.getString(R.string.step_length),
                ActivitySummaryEntries.GROUP_STEPS,
                lineData,
                new DecimalValueFormatter(units.decimals(quantity)),
                unitString
        );
    }

    private static WorkoutChart createCnsToxicityChart(final Context context,
                                                       final List<Entry> cnsToxicityPoints) {
        final String label = String.format("%s(%s)", context.getString(R.string.diving_cns_toxicity), getUnitString(context, UNIT_PERCENTAGE));
        // CNS toxicity decays continuously over time (even during a pause), so it's safe to
        // interpolate across a gap rather than break the segment.
        final LineData lineData = createLineData(context, cnsToxicityPoints, label, ContextCompat.getColor(context, R.color.chart_cns_toxicity));
        final ValueFormatter valueFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        };
        return new WorkoutChart(
                "chart_cns_toxicity",
                context.getString(R.string.diving_cns_toxicity),
                ActivitySummaryEntries.GROUP_DIVING,
                lineData,
                valueFormatter,
                getUnitString(context, UNIT_PERCENTAGE)
        );
    }

    private static WorkoutChart createN2LoadChart(final Context context,
                                                  final List<Entry> n2LoadPoints) {
        final String label = String.format("%s(%s)", context.getString(R.string.diving_nitrogen_load), getUnitString(context, UNIT_PERCENTAGE));
        // N2 load changes continuously over time (even during a pause, as nitrogen off-gasses),
        // so it's safe to interpolate across a gap rather than break the segment.
        final LineData lineData = createLineData(context, n2LoadPoints, label, ContextCompat.getColor(context, R.color.chart_n2_load));
        final ValueFormatter valueFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        };
        return new WorkoutChart(
                "chart_n2_load",
                context.getString(R.string.diving_nitrogen_load),
                ActivitySummaryEntries.GROUP_DIVING,
                lineData,
                valueFormatter,
                getUnitString(context, UNIT_PERCENTAGE)
        );
    }

    private static WorkoutChart createVerticalOscillationChart(final Context context,
                                                               final List<Entry> verticalOscillationPoints) {
        final String label = String.format("%s(%s)", context.getString(R.string.vertical_oscillation), getUnitString(context, UNIT_MM));
        final LineData lineData = createGappedLineData(context, verticalOscillationPoints, label, ContextCompat.getColor(context, R.color.chart_line_stride));
        final ValueFormatter valueFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        };
        return new WorkoutChart(
                "chart_vertical_oscillation",
                context.getString(R.string.vertical_oscillation),
                ActivitySummaryEntries.GROUP_RUNNING_FORM,
                lineData,
                valueFormatter,
                getUnitString(context, UNIT_MM)
        );
    }

    private static WorkoutChart createStanceTimePercentChart(final Context context,
                                                             final List<Entry> stanceTimePercentPoints) {
        final String label = String.format("%s(%s)", context.getString(R.string.stance_time_percent), getUnitString(context, UNIT_PERCENTAGE));
        final LineData lineData = createGappedLineData(context, stanceTimePercentPoints, label, ContextCompat.getColor(context, R.color.chart_line_swolf));
        final ValueFormatter valueFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(java.util.Locale.ROOT, "%.1f", value);
            }
        };
        return new WorkoutChart(
                "chart_stance_time_percent",
                context.getString(R.string.stance_time_percent),
                ActivitySummaryEntries.GROUP_RUNNING_FORM,
                lineData,
                valueFormatter,
                getUnitString(context, UNIT_PERCENTAGE)
        );
    }

    private static WorkoutChart createStanceTimeChart(final Context context,
                                                      final List<Entry> stanceTimePoints) {
        final String label = String.format("%s(%s)", context.getString(R.string.ground_contact_time), getUnitString(context, UNIT_MILLISECONDS));
        final LineData lineData = createGappedLineData(context, stanceTimePoints, label, ContextCompat.getColor(context, R.color.chart_line_step_length));
        final ValueFormatter valueFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        };
        return new WorkoutChart(
                "chart_stance_time",
                context.getString(R.string.ground_contact_time),
                ActivitySummaryEntries.GROUP_RUNNING_FORM,
                lineData,
                valueFormatter,
                getUnitString(context, UNIT_MILLISECONDS)
        );
    }

    private static WorkoutChart createVerticalRatioChart(final Context context,
                                                         final List<Entry> verticalRatioPoints) {
        final String label = String.format("%s(%s)", context.getString(R.string.vertical_ratio), getUnitString(context, UNIT_PERCENTAGE));
        final LineData lineData = createGappedLineData(context, verticalRatioPoints, label, ContextCompat.getColor(context, R.color.chart_line_stamina));
        final ValueFormatter valueFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(java.util.Locale.ROOT, "%.1f", value);
            }
        };
        return new WorkoutChart(
                "chart_vertical_ratio",
                context.getString(R.string.vertical_ratio),
                ActivitySummaryEntries.GROUP_RUNNING_FORM,
                lineData,
                valueFormatter,
                getUnitString(context, UNIT_PERCENTAGE)
        );
    }

    private static WorkoutChart createStanceTimeBalanceChart(final Context context,
                                                             final List<Entry> stanceTimeBalancePoints) {
        final String label = String.format("%s(%s)", context.getString(R.string.ground_contact_time_balance), getUnitString(context, UNIT_PERCENTAGE));
        final LineData lineData = createGappedLineData(context, stanceTimeBalancePoints, label, ContextCompat.getColor(context, R.color.chart_line_body_energy));
        final ValueFormatter valueFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(java.util.Locale.ROOT, "%.1f", value);
            }
        };
        return new WorkoutChart(
                "chart_stance_time_balance",
                context.getString(R.string.ground_contact_time_balance),
                ActivitySummaryEntries.GROUP_RUNNING_FORM,
                lineData,
                valueFormatter,
                getUnitString(context, UNIT_PERCENTAGE)
        );
    }

    private static WorkoutChart createPerformanceConditionChart(final Context context,
                                                                final List<Entry> performanceConditionPoints) {
        final String label = context.getString(R.string.performance_condition);
        final LineData lineData = createGappedLineData(context, performanceConditionPoints, label, ContextCompat.getColor(context, R.color.chart_line_elevation));
        final ValueFormatter valueFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        };
        return new WorkoutChart(
                "chart_performance_condition",
                context.getString(R.string.performance_condition),
                ActivitySummaryEntries.GROUP_PERFORMANCE_CONDITION,
                lineData,
                valueFormatter,
                ""
        );
    }

    private static WorkoutChartUnits chartUnits() {
        return new WorkoutChartUnits(
                GBApplication.getPrefs().getDistanceUnit(),
                GBApplication.getPrefs().getTemperatureUnit()
        );
    }

    private static WorkoutChartUnits chartUnits(final ActivityKind activityKind) {
        return new WorkoutChartUnits(
                GBApplication.getPrefs().getDistanceUnit(),
                GBApplication.getPrefs().getTemperatureUnit(),
                activityKind,
                GBApplication.getPrefs().getBoolean("units_nautical", true)
        );
    }

    /**
     * Converts the plotted metric values to the display unit so the chart library picks round ticks
     * in that unit's domain. The label formatter then only has to render decimals, never convert.
     */
    private static List<Entry> convertPoints(final List<Entry> points,
                                             final WorkoutChartUnits units,
                                             final WorkoutChartUnits.Quantity quantity) {
        final List<Entry> converted = new ArrayList<>(points.size());
        for (final Entry entry : points) {
            converted.add(new Entry(entry.getX(), (float) units.convert(quantity, entry.getY())));
        }
        return converted;
    }

    // A gap between consecutive points larger than this multiple of the series' median
    // sample gap starts a new segment, and is not bridged by an interpolated line.
    private static final float GAP_THRESHOLD_FACTOR = 5f;

    // Failsafe for devices with noisy data / too many gaps.
    private static final int MAX_SEGMENTS = 50;

    /**
     * Splits a chronological entry list into segments, starting a new segment after any gap that is
     * larger than to the series' own median sample gap (e.g. a paused workout, or a sensor dropout).
     * Each segment is later rendered as its own {@link LineDataSet}, so the chart does not draw a
     * misleading interpolated line across the gap.
     */
    private static List<List<Entry>> splitOnGaps(final List<Entry> entries) {
        final List<List<Entry>> segments = new ArrayList<>();
        if (entries.isEmpty()) {
            return segments;
        }
        if (entries.size() < 3) {
            segments.add(entries);
            return segments;
        }

        final float[] gaps = new float[entries.size() - 1];
        for (int i = 1; i < entries.size(); i++) {
            gaps[i - 1] = entries.get(i).getX() - entries.get(i - 1).getX();
        }
        final float[] sortedGaps = gaps.clone();
        Arrays.sort(sortedGaps);
        final float medianGap = sortedGaps[sortedGaps.length / 2];
        if (medianGap <= 0) {
            // Should never happen? No meaningful gap to compare against (e.g. duplicate timestamps), keep as one segment.
            segments.add(entries);
            return segments;
        }

        final float gapThreshold = medianGap * GAP_THRESHOLD_FACTOR;
        List<Entry> currentSegment = new LinkedList<>();
        currentSegment.add(entries.get(0));
        for (int i = 1; i < entries.size(); i++) {
            if (gaps[i - 1] > gapThreshold) {
                segments.add(currentSegment);
                if (segments.size() >= MAX_SEGMENTS) {
                    segments.clear();
                    segments.add(entries);
                    return segments;
                }
                currentSegment = new LinkedList<>();
            }
            currentSegment.add(entries.get(i));
        }
        segments.add(currentSegment);
        return segments;
    }

    private static LineData createGappedLineData(final Context context,
                                                 final List<Entry> entries,
                                                 final String label,
                                                 final int color) {
        final List<ILineDataSet> dataSets = new ArrayList<>();
        // Every segment keeps the same label
        for (final List<Entry> segment : splitOnGaps(entries)) {
            dataSets.add(createLineDataSet(context, segment, label, color));
        }
        return new LineData(dataSets);
    }

    private static LineData createLineData(final Context context,
                                           final List<Entry> entries,
                                           final String label,
                                           final int color) {
        return new LineData(createLineDataSet(context, entries, label, color));
    }

    public static LineDataSet createLineDataSet(final Context context,
                                                final List<Entry> entities,
                                                final String label,
                                                final int color) {
        final LineDataSet dataSet = new LineDataSet(entities, label);
        dataSet.setMode(LineDataSet.Mode.HORIZONTAL_BEZIER);
        dataSet.setCubicIntensity(0.05f);
        dataSet.setDrawCircles(false);
        dataSet.setAxisDependency(YAxis.AxisDependency.RIGHT);
        dataSet.setColor(color);
        dataSet.setValueTextColor(GBApplication.getSecondaryTextColor(context));
        dataSet.setLineWidth(1.5f);
        dataSet.setHighlightLineWidth(2f);
        dataSet.setDrawValues(false);
        dataSet.setDrawHorizontalHighlightIndicator(false);
        return dataSet;
    }

    public static ScatterDataSet createScatterDataSet(final Context context,
                                                      final List<Entry> entities,
                                                      final String label,
                                                      final int color) {
        final ScatterDataSet dataSet = new ScatterDataSet(entities, label);
        dataSet.setAxisDependency(YAxis.AxisDependency.RIGHT);
        dataSet.setColor(color);
        dataSet.setValueTextColor(GBApplication.getSecondaryTextColor(context));
        dataSet.setHighlightLineWidth(2f);
        dataSet.setDrawValues(false);
        dataSet.setDrawHorizontalHighlightIndicator(false);
        dataSet.setScatterShape(ScatterChart.ScatterShape.CIRCLE);
        dataSet.setScatterShapeSize(10f);
        return dataSet;
    }

    public static String getCadenceUnit(final ActivityKind.CycleUnit unit) {
        return switch (unit) {
            case STROKES -> ActivitySummaryEntries.UNIT_STROKES_PER_MINUTE;
            case JUMPS -> ActivitySummaryEntries.UNIT_JUMPS_PER_MINUTE;
            case REPS -> ActivitySummaryEntries.UNIT_REPS_PER_MINUTE;
            case REVOLUTIONS -> ActivitySummaryEntries.UNIT_REVS_PER_MINUTE;
            default -> UNIT_SPM;
        };
    }
}
