package cn.swjtu.geolog;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class StatusFragment extends Fragment implements MeasurementListener {

    private ProgressBar mProgressAdrValid, mProgressCycleSlips, mProgressMultipath;
    private TextView mTextAdrValid, mTextCycleSlips, mTextMultipath;
    private LinearLayout mContainerQualityIndicators;
    private LinearLayout mContainerAvgCn0;
    private TableLayout mTableSysStatus;
    private TextView mTextTimeDate, mTextHardware, mTextHwYear, mTextPlatformInfo;

    private static final int ADR_STATE_VALID = GnssMeasurement.ADR_STATE_VALID;
    private static final int ADR_STATE_CYCLE_SLIP = GnssMeasurement.ADR_STATE_CYCLE_SLIP;

    private final long uiThrottleMs = 500; // throttle UI updates
    private long lastUiUpdate = 0;
    private FieldLogger fieldLogger;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_status, container, false);

        mContainerQualityIndicators = view.findViewById(R.id.container_quality_indicators);

        mContainerAvgCn0 = view.findViewById(R.id.container_avg_cn0);
        mTableSysStatus = view.findViewById(R.id.table_sys_status);

        mTextTimeDate = view.findViewById(R.id.text_time_date);
        mTextHardware = view.findViewById(R.id.text_hardware);
        mTextHwYear = view.findViewById(R.id.text_hw_year);
        mTextPlatformInfo = view.findViewById(R.id.text_platform_info);

        updateDeviceInfo();
        // init field logger
        fieldLogger = new FieldLogger(requireContext(), "gnss-status");
        fieldLogger.write("StatusFragment created, log at: " + fieldLogger.path());
        return view;
    }

    private void updateDeviceInfo() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        mTextHardware.setText("Manu / Mod: " + manufacturer + " / " + model);

        try {
            LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
            Method method = lm.getClass().getMethod("getGnssYearOfHardware");
            int hwYear = (int) method.invoke(lm);
            String cpuName = Build.HARDWARE;
            mTextHwYear.setText("HW Mod / Year: " + cpuName + " / " + (hwYear == 0 ? "Unknown" : hwYear));
        } catch (Exception e) {
             String cpuName = Build.HARDWARE;
             mTextHwYear.setText("HW Mod / Year: " + cpuName + " / Unknown");
        }

        mTextPlatformInfo.setText("Plat / API Lvl: Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);

    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
             MeasurementProvider provider = ((MainActivity) getActivity()).getMeasurementProvider();
             if (provider != null) {
                 provider.addListener(this);
             }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getActivity() instanceof MainActivity) {
             MeasurementProvider provider = ((MainActivity) getActivity()).getMeasurementProvider();
             if (provider != null) {
                 provider.removeListener(this);
             }
        }
    }

    @Override
    public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
        if (!isAdded() || getActivity() == null || getView() == null || event == null) return;
        long now = System.currentTimeMillis();
        if (now - lastUiUpdate < uiThrottleMs) return; // throttle
        lastUiUpdate = now;
        try {
            int n = (event.getMeasurements() != null) ? event.getMeasurements().size() : -1;
            fieldLogger.write("Measurements count=" + n);
        } catch (Throwable ignore) {}
        getActivity().runOnUiThread(() -> {
            try {
                updateUI(event);
            } catch (Throwable uiEx) {
                fieldLogger.write("updateUI error: " + uiEx.getClass().getName() + ": " + uiEx.getMessage());
            }
        });
    }

    private void updateUI(GnssMeasurementsEvent event) {
        if (event == null || event.getMeasurements() == null) return;

        GnssClock clock = event.getClock();
        if (clock != null) {
            if (clock.hasFullBiasNanos()) {
                 long timeNanos = clock.getTimeNanos();
                 double fullBiasNs = clock.getFullBiasNanos();
                 double biasNs = clock.hasBiasNanos() ? clock.getBiasNanos() : 0.0;
                 long gpsTimeNs = timeNanos - (long)(fullBiasNs + biasNs);
                 // GPS Epoch (1980-01-06) to Unix Epoch (1970-01-01) is +315964800 seconds
                 long gpsToUnixMs = 315964800000L;
                 long timeMs = (gpsTimeNs / 1000000L) + gpsToUnixMs;

                 SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd / HH:mm:ss", Locale.US);
                 sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                 mTextTimeDate.setText("GPS Date / Time: " + sdf.format(new Date(timeMs)));
            } else {
                 mTextTimeDate.setText("Time (Sys): " + new SimpleDateFormat("yyyy-MM-dd / HH:mm:ss", Locale.US).format(new Date()));
            }
        }

        Map<String, QualityStats> qualityMap = new HashMap<>();
        Map<String, List<Double>> cn0Map = new HashMap<>();
        Map<String, int[]> sysStatusMap = new HashMap<>();

        for (GnssMeasurement m : event.getMeasurements()) {
            double freqMhz = m.getCarrierFrequencyHz() / 1e6;
            String freqLabel = getFrequencyGroupLabel(freqMhz);
            String sysKey = getGroupKey(m); // Original key for System Status

            // Quality Stats - use Freq Label
            if (!qualityMap.containsKey(freqLabel)) qualityMap.put(freqLabel, new QualityStats());
            QualityStats stats = qualityMap.get(freqLabel);
            stats.total++;
            if ((m.getAccumulatedDeltaRangeState() & ADR_STATE_VALID) != 0) stats.validAdr++;
            if ((m.getAccumulatedDeltaRangeState() & ADR_STATE_CYCLE_SLIP) != 0) stats.cycleSlip++;
            if (m.getMultipathIndicator() == GnssMeasurement.MULTIPATH_INDICATOR_DETECTED) stats.multipath++;

            // C/N0 Map - use System Key (Restored)
            if (!cn0Map.containsKey(sysKey)) {
                cn0Map.put(sysKey, new ArrayList<>());
            }
            cn0Map.get(sysKey).add(m.getCn0DbHz());

            // Sys Status Map: [Total, Used, Unused] - Use System Key
            if (!sysStatusMap.containsKey(sysKey)) {
                sysStatusMap.put(sysKey, new int[]{0, 0, 0});
            }
            int[] counts = sysStatusMap.get(sysKey);
            counts[0]++; // Total
            if ((m.getState() & GnssMeasurement.STATE_CODE_LOCK) != 0) {
                counts[1]++; // Used
            } else {
                counts[2]++; // Unused
            }
        }

        List<String> qualityKeys = new ArrayList<>(qualityMap.keySet());
        Collections.sort(qualityKeys);

        // Update Quality Indicators
        updateQualityContainer(qualityMap, qualityKeys);

        // Update Avg C/N0 (Histogram style)
        mContainerAvgCn0.removeAllViews();
        List<String> cn0Keys = new ArrayList<>(cn0Map.keySet());
        Collections.sort(cn0Keys);
        for (String key : cn0Keys) {
            if (!cn0Map.containsKey(key)) continue;
            List<Double> values = cn0Map.get(key);
            double avg = 0;
            for (Double v : values) avg += v;
            avg /= values.size();

            addHistogramRow(key, avg);
        }

        // Update System Status Table
        List<String> sysKeys = new ArrayList<>(sysStatusMap.keySet());
        Collections.sort(sysKeys);
        updateSysStatusTable(sysStatusMap, sysKeys);
    }

    private String getFrequencyGroupLabel(double freqMhz) {
        if (Math.abs(freqMhz - 1575.42) < 5.0) return "L1, E1, B1C";
        if (Math.abs(freqMhz - 1227.60) < 5.0) return "L2";
        if (Math.abs(freqMhz - 1176.45) < 5.0) return "L5, E5a, B2a";
        if (Math.abs(freqMhz - 1602.0) < 25.0) return "G1";
        if (Math.abs(freqMhz - 1246.0) < 25.0) return "G2";
        if (Math.abs(freqMhz - 1202.0) < 5.0) return "G3";
        if (Math.abs(freqMhz - 1207.14) < 5.0) return "E5b, B2b";
        if (Math.abs(freqMhz - 1191.795) < 5.0) return "E5";
        if (Math.abs(freqMhz - 1278.75) < 5.0) return "E6, L6";
        if (Math.abs(freqMhz - 1561.098) < 5.0) return "B1I";
        if (Math.abs(freqMhz - 1268.52) < 5.0) return "B3I";
        return String.format(Locale.US, "%.1fMHz", freqMhz);
    }

    private void updateQualityContainer(Map<String, QualityStats> qualityMap, List<String> sortedKeys) {
        mContainerQualityIndicators.removeAllViews();

        int padding8 = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        int marginBottom = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        float elevation4 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics());
        float radius8 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());

        for (String key : sortedKeys) {
            QualityStats stats = qualityMap.get(key);

            CardView card = new CardView(getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, marginBottom); // Spacing between cards
            card.setLayoutParams(lp);
            card.setCardBackgroundColor(Color.WHITE);
            card.setCardElevation(elevation4);
            card.setRadius(radius8);
            card.setContentPadding(padding8, padding8, padding8, padding8);

            LinearLayout section = new LinearLayout(getContext());
            section.setOrientation(LinearLayout.VERTICAL);

            TextView title = new TextView(getContext());
            title.setText(key);
            title.setTypeface(null, Typeface.BOLD);
            title.setTextColor(Color.BLACK);
            section.addView(title);

            addQualityRow(section, "ADR State", stats.validAdr, stats.total, Color.GREEN);
            addQualityRow(section, "Cycle Slips", stats.cycleSlip, stats.total, Color.RED);
            addQualityRow(section, "Multipaths", stats.multipath, stats.total, Color.RED);

            card.addView(section);
            mContainerQualityIndicators.addView(card);
        }
    }

    private void addQualityRow(LinearLayout parent, String label, int count, int total, int countColor) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvLabel = new TextView(getContext());
        tvLabel.setText(label);
        tvLabel.setWidth(250);
        tvLabel.setTextColor(Color.BLACK);
        row.addView(tvLabel);

        ProgressBar pb = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        pb.setLayoutParams(params);
        if (total > 0) {
             pb.setMax(total);
             pb.setProgress(count);
        } else {
             pb.setMax(100);
             pb.setProgress(0);
        }
        row.addView(pb);

        TextView tvValue = new TextView(getContext());
        String text = count + "/" + total;
        android.text.SpannableString ss = new android.text.SpannableString(text);
        ss.setSpan(new android.text.style.ForegroundColorSpan(countColor), 0, String.valueOf(count).length(), 0);
        ss.setSpan(new android.text.style.ForegroundColorSpan(Color.BLACK), String.valueOf(count).length(), text.length(), 0);
        tvValue.setText(ss);
        tvValue.setPadding(16, 0, 0, 0);
        row.addView(tvValue);

        parent.addView(row);
    }

    private static class QualityStats {
        int total = 0;
        int validAdr = 0;
        int cycleSlip = 0;
        int multipath = 0;
    }

    private void addHistogramRow(String label, double avgCn0) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 4, 0, 4);

        TextView tvLabel = new TextView(getContext());
        tvLabel.setText(label);
        tvLabel.setWidth(200); // Fixed width for label
        tvLabel.setTextColor(Color.BLACK);
        tvLabel.setTypeface(null, Typeface.BOLD);
        row.addView(tvLabel);

        ProgressBar pb = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        pb.setLayoutParams(params);
        pb.setMax(60); // Max DBHz usually around 50-60
        pb.setProgress((int)avgCn0);
        // Change color based on system?
        if (label.startsWith("GPS")) pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.BLUE));
        else if (label.startsWith("GAL")) pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.MAGENTA));
        else if (label.startsWith("BDS")) pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.GREEN));
        else if (label.startsWith("GLO")) pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.RED));
        else pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.GRAY));

        row.addView(pb);

        TextView tvValue = new TextView(getContext());
        tvValue.setText(String.format(Locale.US, "%.1f", avgCn0));
        tvValue.setPadding(8, 0, 0, 0);
        tvValue.setTextColor(Color.BLACK);
        row.addView(tvValue);

        mContainerAvgCn0.addView(row);
    }

    private void updateSysStatusTable(Map<String, int[]> data, List<String> sortedKeys) {
        // Keep header
        int childCount = mTableSysStatus.getChildCount();
        if (childCount > 1) {
            mTableSysStatus.removeViews(1, childCount - 1);
        }

        for (String key : sortedKeys) {
            int[] counts = data.get(key);
            TableRow row = new TableRow(getContext());

            TextView tvName = new TextView(getContext());
            tvName.setText(key);
            tvName.setTextColor(Color.BLACK);
            row.addView(tvName);

            TextView tvTot = new TextView(getContext());
            tvTot.setText(String.valueOf(counts[0]));
            tvTot.setTextColor(Color.BLACK);
            tvTot.setPadding(16, 0, 0, 0);
            row.addView(tvTot);

            TextView tvUse = new TextView(getContext());
            tvUse.setText(String.valueOf(counts[1]));
            tvUse.setTextColor(Color.parseColor("#99CC00")); // Light Green
            tvUse.setPadding(16, 0, 0, 0);
            row.addView(tvUse);

            TextView tvUnu = new TextView(getContext());
            tvUnu.setText(String.valueOf(counts[2]));
            tvUnu.setTextColor(Color.parseColor("#FF4444")); // Light Red
            tvUnu.setPadding(16, 0, 0, 0);
            row.addView(tvUnu);

            mTableSysStatus.addView(row);
        }
    }

    // Copied from LoggerFragment and adapted
    private String getGroupKey(GnssMeasurement m) {
        int constType = m.getConstellationType();
        double freqMhz = m.getCarrierFrequencyHz() / 1e6;

        String sys = getSystemName(constType);
        String band = getCarrierFrequencyLabel(constType, freqMhz);

        return sys + " " + band;
    }

    private String getCarrierFrequencyLabel(int constellationType, double freqMhz) {
         final double TOLERANCE = 5.0;
         if (constellationType == GnssStatus.CONSTELLATION_GPS) {
             if (Math.abs(freqMhz - 1575.42) < TOLERANCE) return "L1";
             if (Math.abs(freqMhz - 1227.60) < TOLERANCE) return "L2";
             if (Math.abs(freqMhz - 1176.45) < TOLERANCE) return "L5";
         } else if (constellationType == GnssStatus.CONSTELLATION_GLONASS) {
             if (Math.abs(freqMhz - 1602.0) < 15.0) return "G1";
             if (Math.abs(freqMhz - 1246.0) < 15.0) return "G2";
             if (Math.abs(freqMhz - 1202.025) < TOLERANCE) return "G3";
         } else if (constellationType == GnssStatus.CONSTELLATION_GALILEO) {
             if (Math.abs(freqMhz - 1575.42) < TOLERANCE) return "E1";
             if (Math.abs(freqMhz - 1176.45) < TOLERANCE) return "E5a";
             if (Math.abs(freqMhz - 1207.14) < TOLERANCE) return "E5b";
             if (Math.abs(freqMhz - 1191.795) < TOLERANCE) return "E5";
             if (Math.abs(freqMhz - 1278.75) < TOLERANCE) return "E6";
         } else if (constellationType == GnssStatus.CONSTELLATION_BEIDOU) {
             if (Math.abs(freqMhz - 1561.098) < TOLERANCE) return "B1I";
             if (Math.abs(freqMhz - 1575.42) < TOLERANCE) return "B1C";
             if (Math.abs(freqMhz - 1207.14) < TOLERANCE) return "B2b";
             if (Math.abs(freqMhz - 1176.45) < TOLERANCE) return "B2a";
             if (Math.abs(freqMhz - 1268.52) < TOLERANCE) return "B3I";
         } else if (constellationType == GnssStatus.CONSTELLATION_QZSS) {
             if (Math.abs(freqMhz - 1575.42) < TOLERANCE) return "L1";
             if (Math.abs(freqMhz - 1227.60) < TOLERANCE) return "L2";
             if (Math.abs(freqMhz - 1176.45) < TOLERANCE) return "L5";
             if (Math.abs(freqMhz - 1278.75) < TOLERANCE) return "L6";
         }
         return String.format(Locale.US, "%.0fMHz", freqMhz);
    }

    private String getSystemName(int type) {
        switch(type) {
            case GnssStatus.CONSTELLATION_GPS: return "GPS";
            case GnssStatus.CONSTELLATION_GLONASS: return "GLO";
            case GnssStatus.CONSTELLATION_GALILEO: return "GAL";
            case GnssStatus.CONSTELLATION_BEIDOU: return "BDS";
            case GnssStatus.CONSTELLATION_QZSS: return "QZS";
            default: return "UNK";
        }
    }

    public void onProviderEnabled(String provider) {}
    public void onProviderDisabled(String provider) {}
    public void onLocationChanged(Location location) {}
    public void onLocationStatusChanged(String provider, int status, Bundle extras) {}
    public void onGnssMeasurementsStatusChanged(int status) {}
    public void onGnssNavigationMessageReceived(GnssNavigationMessage event) {}
    public void onGnssNavigationMessageStatusChanged(int status) {}
    public void onGnssStatusChanged(GnssStatus gnssStatus) {}
    public void onListenerRegistration(String listener, boolean result) {}
    public void onNmeaReceived(long l, String s) {}
    public void onTTFFReceived(long l) {}
}
