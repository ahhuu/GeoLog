/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.swjtu.geolog;

import static com.google.common.base.Preconditions.checkArgument;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.GnssStatus;
import android.location.GnssClock;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View.OnClickListener;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import cn.swjtu.geolog.TimerService.TimerBinder;
import cn.swjtu.geolog.TimerService.TimerListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import android.text.Html;
import android.os.Build;

/** The UI fragment that hosts a logging view. */
public class LoggerFragment extends Fragment implements TimerListener, MeasurementListener {
    private static final String TIMER_FRAGMENT_TAG = "timer";

    private RecyclerView mRecyclerView;
    private MeasurementAdapter mAdapter;
    private FileLogger mFileLogger;
    private UiLogger mUiLogger;
    private Button mStartLog;
    private Button mTimer;
    private Button mSendFile;
    private TextView mTimerDisplay;
    private TimerService mTimerService;
    private TimerValues mTimerValues =
            new TimerValues(0 /* hours */, 0 /* minutes */, 0 /* seconds */);

    private final BroadcastReceiver mBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    checkArgument(intent != null, "Intent is null");
                    short intentType =
                            intent.getByteExtra(TimerService.EXTRA_KEY_TYPE, TimerService.TYPE_UNKNOWN);
                    switch (intentType) {
                        case TimerService.TYPE_UPDATE:
                        case TimerService.TYPE_FINISH:
                            break;
                        default:
                            return;
                    }
                    TimerValues countdown =
                            new TimerValues(intent.getLongExtra(TimerService.EXTRA_KEY_UPDATE_REMAINING, 0L));
                    LoggerFragment.this.displayTimer(countdown, true /* countdownStyle */);
                    if (intentType == TimerService.TYPE_FINISH) {
                        LoggerFragment.this.stopAndSend();
                    }
                }
            };
    private ServiceConnection mConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName className, IBinder serviceBinder) {
                    mTimerService = ((TimerBinder) serviceBinder).getService();
                }

                @Override
                public void onServiceDisconnected(ComponentName className) {
                    mTimerService = null;
                }
            };

    private final UIFragmentComponent mUiComponent = new UIFragmentComponent();

    public void setUILogger(UiLogger value) {
        mUiLogger = value;
    }

    public void setFileLogger(FileLogger value) {
        mFileLogger = value;
    }

    public UIFragmentComponent getUIFragmentComponent() {
        return mUiComponent;
    }

    @Override
    public void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(getActivity())
                .registerReceiver(mBroadcastReceiver, new IntentFilter(TimerService.TIMER_ACTION));
        if (getActivity() instanceof MainActivity) {
            MeasurementProvider provider = ((MainActivity) getActivity()).getMeasurementProvider();
            if (provider != null) {
                provider.addListener(this);
            }
        }
    }

    @Override
    public void onPause() {
        LocalBroadcastManager.getInstance(getActivity()).unregisterReceiver(mBroadcastReceiver);
        if (getActivity() instanceof MainActivity) {
            MeasurementProvider provider = ((MainActivity) getActivity()).getMeasurementProvider();
            if (provider != null) {
                provider.removeListener(this);
            }
        }
        super.onPause();
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View newView = inflater.inflate(R.layout.fragment_log, container, false);

        mRecyclerView = newView.findViewById(R.id.log_recycler);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mAdapter = new MeasurementAdapter();
        mRecyclerView.setAdapter(mAdapter);

        getActivity()
                .bindService(
                        new Intent(getActivity(), TimerService.class), mConnection, Context.BIND_AUTO_CREATE);

        UiLogger currentUiLogger = mUiLogger;
        if (currentUiLogger != null) {
            currentUiLogger.setUiFragmentComponent(mUiComponent);
        }
        FileLogger currentFileLogger = mFileLogger;
        if (currentFileLogger != null) {
            currentFileLogger.setUiComponent(mUiComponent);
        }

        Button clear = newView.findViewById(R.id.clear_log);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mAdapter.clear();
            }
        });

        Button scrollTop = newView.findViewById(R.id.scroll_top);
        scrollTop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mRecyclerView.scrollToPosition(0);
            }
        });

        Button scrollBottom = newView.findViewById(R.id.scroll_bottom);
        scrollBottom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAdapter.getItemCount() > 0) {
                    mRecyclerView.scrollToPosition(mAdapter.getItemCount() - 1);
                }
            }
        });

        mTimerDisplay = newView.findViewById(R.id.timer_display);
        mTimer = newView.findViewById(R.id.timer);
        mStartLog = newView.findViewById(R.id.start_logs);
        mSendFile = newView.findViewById(R.id.send_file);

        displayTimer(mTimerValues, false /* countdownStyle */);
        enableOptions(true /* start */);

        mStartLog.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        enableOptions(false /* start */);
                        Toast.makeText(getContext(), R.string.start_message, Toast.LENGTH_LONG).show();
                        mFileLogger.startNewLog();
                        if (!mTimerValues.isZero() && (mTimerService != null)) {
                            mTimerService.startTimer();
                        }
                    }
                });

        mSendFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopAndSend();
            }
        });

        mTimer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                launchTimerDialog();
            }
        });

        return newView;
    }

    void stopAndSend() {
        if (mTimerService != null) {
            mTimerService.stopTimer();
        }
        enableOptions(true /* start */);
        Toast.makeText(getContext(), R.string.stop_message, Toast.LENGTH_LONG).show();
        displayTimer(mTimerValues, false /* countdownStyle */);
        mFileLogger.send();
    }

    void displayTimer(TimerValues values, boolean countdownStyle) {
        String content = countdownStyle ? values.toCountdownString() : values.toString();
        mTimerDisplay.setText(
                String.format("%s: %s", getResources().getString(R.string.timer_display), content));
    }

    @Override
    public void processTimerValues(TimerValues values) {
        if (mTimerService != null) {
            mTimerService.setTimer(values);
        }
        mTimerValues = values;
        displayTimer(mTimerValues, false /* countdownStyle */);
    }

    private void launchTimerDialog() {
        TimerFragment timer = new TimerFragment();
        timer.setTargetFragment(this, 0);
        timer.setArguments(mTimerValues.toBundle());
        timer.show(getFragmentManager(), TIMER_FRAGMENT_TAG);
    }

    private void enableOptions(boolean start) {
        mTimer.setEnabled(start);
        mStartLog.setEnabled(start);
        mSendFile.setEnabled(!start);
    }

    @Override
    public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mAdapter.update(event);
                SharedPreferences sp = android.preference.PreferenceManager.getDefaultSharedPreferences(getContext());
                boolean autoScroll = sp.getBoolean(SettingsFragment.PREFERENCE_KEY_AUTO_SCROLL, false);
                if (autoScroll && mAdapter.getItemCount() > 0) {
                    mRecyclerView.smoothScrollToPosition(mAdapter.getItemCount() - 1);
                }
            }
        });
    }

    // Implicit implementations
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

    public class UIFragmentComponent {
        public void logTextFragment(final String tag, final String text, int color) {
            // Disabled text logging to view
        }
        public void startActivity(Intent intent) {
            getActivity().startActivity(intent);
        }
    }

    // Adapter Class
    private class MeasurementAdapter extends RecyclerView.Adapter<MeasurementAdapter.ViewHolder> {
        private final List<MeasurementGroup> mGroups = new ArrayList<>();
        // State tracking for expansion
        private final Map<String, Boolean> mExpandedState = new HashMap<>(); // Key -> Expanded State
        private final Map<String, Boolean> mExpandedQuality = new HashMap<>();
        private final Map<String, Boolean> mExpandedUncertainty = new HashMap<>();

        public void clear() {
            mGroups.clear();
            notifyDataSetChanged();
        }

        public void update(GnssMeasurementsEvent event) {
            Map<String, List<GnssMeasurement>> grouped = new HashMap<>();

            for (GnssMeasurement m : event.getMeasurements()) {
                String key = getGroupKey(m);
                if (!grouped.containsKey(key)) {
                    grouped.put(key, new ArrayList<>());
                }
                grouped.get(key).add(m);
            }

            mGroups.clear();
            for (Map.Entry<String, List<GnssMeasurement>> entry : grouped.entrySet()) {
                mGroups.add(new MeasurementGroup(entry.getKey(), entry.getValue(), event.getClock()));
            }
            // Sort? By Name
            Collections.sort(mGroups, new java.util.Comparator<MeasurementGroup>() {
                @Override
                public int compare(MeasurementGroup o1, MeasurementGroup o2) {
                    return o1.title.compareTo(o2.title);
                }
            });
            notifyDataSetChanged();
        }

        private String getGroupKey(GnssMeasurement m) {
            int constType = m.getConstellationType();
            double freqMhz = m.getCarrierFrequencyHz() / 1e6;

            String sys = getSystemName(constType);
            String band = getCarrierFrequencyLabel(constType, freqMhz);
            String code = getCodeLabel(m, band);

            return String.format(Locale.US, "%s %s %s %.2fMHz", sys, band, code, freqMhz);
        }

        private String getCodeLabel(GnssMeasurement m, String band) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                String type = m.getCodeType();
                if (type == null) return "";
                // Mapping simplifications
                if (m.getConstellationType() == GnssStatus.CONSTELLATION_GPS) {
                    if ("C".equals(type)) return "C/A"; // Common mapping
                    if ("P".equals(type)) return "P";
                    // Add more mappings as needed
                }
                return type;
            }
            return "";
        }

        private String getCarrierFrequencyLabel(int constellationType, double freqMhz) {
             final double TOLERANCE = 5.0; // MHz
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
                 if (Math.abs(freqMhz - 1207.14) < TOLERANCE) return "B2b"; // or B2I
                 if (Math.abs(freqMhz - 1176.45) < TOLERANCE) return "B2a";
                 if (Math.abs(freqMhz - 1268.52) < TOLERANCE) return "B3I";
             } else if (constellationType == GnssStatus.CONSTELLATION_QZSS) {
                 if (Math.abs(freqMhz - 1575.42) < TOLERANCE) return "L1";
                 if (Math.abs(freqMhz - 1227.60) < TOLERANCE) return "L2";
                 if (Math.abs(freqMhz - 1176.45) < TOLERANCE) return "L5";
                 if (Math.abs(freqMhz - 1278.75) < TOLERANCE) return "L6";
             }
             return "";
        }

        private String getSystemName(int type) {
            switch(type) {
                case GnssStatus.CONSTELLATION_GPS: return "GPS";
                case GnssStatus.CONSTELLATION_GLONASS: return "GLONASS";
                case GnssStatus.CONSTELLATION_GALILEO: return "Galileo";
                case GnssStatus.CONSTELLATION_BEIDOU: return "BDS";
                case GnssStatus.CONSTELLATION_QZSS: return "QZSS";
                default: return "Unknown";
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_measurement_group, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            MeasurementGroup group = mGroups.get(position);
            holder.bind(group);
        }

        @Override
        public int getItemCount() {
            return mGroups.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView title, hwClock;
            TextView headState, contentState;
            TextView headQuality, contentQuality;
            TextView headUncertainty, contentUncertainty;

            ViewHolder(View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.header_title);
                hwClock = itemView.findViewById(R.id.text_hw_clock);
                headState = itemView.findViewById(R.id.header_state);
                contentState = itemView.findViewById(R.id.content_state);
                headQuality = itemView.findViewById(R.id.header_quality);
                contentQuality = itemView.findViewById(R.id.content_quality);
                headUncertainty = itemView.findViewById(R.id.header_uncertainty);
                contentUncertainty = itemView.findViewById(R.id.content_uncertainty);

                headState.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggle(contentState, mExpandedState);
                    }
                });
                headQuality.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                         toggle(contentQuality, mExpandedQuality);
                    }
                });
                headUncertainty.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggle(contentUncertainty, mExpandedUncertainty);
                    }
                });
            }

            void toggle(View content, Map<String, Boolean> map) {
                int pos = getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                String key = mGroups.get(pos).title;
                boolean isExpanded = content.getVisibility() == View.VISIBLE;
                content.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
                map.put(key, !isExpanded);
            }

            void bind(MeasurementGroup group) {
                // Change "GPS L1 C/A" to "GNSSSignalInfo" as requested
                // Reverted as per instruction 2: "GPS L1 C/A" to "GNSSSignalInfo"将这部分转换代码删除，恢复原样显示
                title.setText(group.title);
                // Access hardware clock directly as check method is missing in current env
                hwClock.setText("HW Discontinuity: " + group.clock.getHardwareClockDiscontinuityCount());

                // Restore Expansion
                contentState.setVisibility(mExpandedState.getOrDefault(group.title, false) ? View.VISIBLE : View.GONE);
                contentQuality.setVisibility(mExpandedQuality.getOrDefault(group.title, false) ? View.VISIBLE : View.GONE);
                contentUncertainty.setVisibility(mExpandedUncertainty.getOrDefault(group.title, false) ? View.VISIBLE : View.GONE);

                // Populate Content
                StringBuilder stateSb = new StringBuilder();
                StringBuilder qualitySb = new StringBuilder();
                StringBuilder uncSb = new StringBuilder();

                double totalCn0 = 0;
                int count = 0;

                // Aggregates for Uncertainty
                double sumTimeUnc = 0; // ReceivedSvTimeUncertaintyNanos
                double sumBaseBandCn0 = 0;
                double sumDopplerUnc = 0;
                double sumAdrUnc = 0;
                double sumPhaseUnc = 0;
                double sumSatIsbUnc = 0;
                double sumFullIsbUnc = 0;

                int countBaseBandCn0 = 0;
                int countTimeUnc = 0;
                int countDopplerUnc = 0;
                int countAdrUnc = 0;
                int countPhaseUnc = 0;
                int countSatIsbUnc = 0;
                int countFullIsbUnc = 0;

                for (GnssMeasurement m : group.items) {
                    // Start of line
                    String lineStart = "Svid " + m.getSvid() + ": ";

                    if (stateSb.length() > 0) stateSb.append("<br>");
                    stateSb.append(lineStart).append(getStateString(m.getState()));

                    totalCn0 += m.getCn0DbHz();
                    count++;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                         sumTimeUnc += m.getReceivedSvTimeUncertaintyNanos();
                         countTimeUnc++;

                         sumDopplerUnc += m.getPseudorangeRateUncertaintyMetersPerSecond();
                         countDopplerUnc++;

                         if (m.getAccumulatedDeltaRangeState() != GnssMeasurement.ADR_STATE_UNKNOWN) {
                              sumAdrUnc += m.getAccumulatedDeltaRangeUncertaintyMeters();
                              countAdrUnc++;
                         }

                         if (m.hasCarrierPhaseUncertainty()) {
                            sumPhaseUnc += m.getCarrierPhaseUncertainty();
                            countPhaseUnc++;
                         }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        if (m.hasBasebandCn0DbHz()) {
                            sumBaseBandCn0 += m.getBasebandCn0DbHz();
                            countBaseBandCn0++;
                        }
                        if (m.hasSatelliteInterSignalBiasUncertaintyNanos()) {
                            sumSatIsbUnc += m.getSatelliteInterSignalBiasUncertaintyNanos();
                            countSatIsbUnc++;
                        }
                        if (m.hasFullInterSignalBiasUncertaintyNanos()) {
                            sumFullIsbUnc += m.getFullInterSignalBiasUncertaintyNanos();
                            countFullIsbUnc++;
                        }
                    }
                }

                if (count > 0) {
                    qualitySb.append("Avg C/N0: ").append(String.format(Locale.US, "%.1f dBHz", totalCn0/count)).append("\n");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && countBaseBandCn0 > 0) {
                        qualitySb.append("Avg Base-Band C/N0: ").append(String.format(Locale.US, "%.1f dBHz", sumBaseBandCn0/countBaseBandCn0)).append("\n");
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                     contentState.setText(Html.fromHtml(stateSb.toString(), Html.FROM_HTML_MODE_LEGACY));
                } else {
                     contentState.setText(Html.fromHtml(stateSb.toString()));
                }

                contentQuality.setText(qualitySb.toString());

                // Uncertainty Section

                // 4. Avg Time Uncertainty (Clock)
                if (group.clock.hasTimeUncertaintyNanos()) {
                    uncSb.append("Avg Time Uncertainty(ns): ").append(String.format(Locale.US, "%.1f", group.clock.getTimeUncertaintyNanos())).append("\n");
                }

                // 5. Avg Bias Uncertainty (Clock)
                if (group.clock.hasBiasUncertaintyNanos()) {
                     uncSb.append("Avg Bias Uncertainty(ns): ").append(String.format(Locale.US, "%.1f", group.clock.getBiasUncertaintyNanos())).append("\n");
                }

                // 6. Avg Drift Uncertainty (Clock)
                if (group.clock.hasDriftUncertaintyNanosPerSecond()) {
                     uncSb.append("Avg Drift Uncertainty(ns): ").append(String.format(Locale.US, "%.1f", group.clock.getDriftUncertaintyNanosPerSecond())).append("\n");
                }

                // 7. Avg Retime Uncertainty (Measurement)
                if (countTimeUnc > 0) {
                    uncSb.append("Avg Retime Uncertainty(ns): ").append(String.format(Locale.US, "%.1f", sumTimeUnc / countTimeUnc)).append("\n");
                }

                // 8. Avg Doppler Uncertainty (Measurement)
                if (countDopplerUnc > 0) {
                    uncSb.append("Avg Doppler Uncertainty(m/s): ").append(String.format(Locale.US, "%.4f", sumDopplerUnc / countDopplerUnc)).append("\n");
                }

                // 9. Avg ADR Uncertainty (Measurement)
                if (countAdrUnc > 0) {
                    uncSb.append("Avg ADR Uncertainty(m): ").append(String.format(Locale.US, "%.4f", sumAdrUnc / countAdrUnc)).append("\n");
                }

                // 10. Avg phase Uncertainty (Measurement)
                if (countPhaseUnc > 0) {
                     uncSb.append("Avg phase Uncertainty(cycle): ").append(String.format(Locale.US, "%.4f", sumPhaseUnc / countPhaseUnc)).append("\n");
                }

                // 11. Avg Full inter-signal bias Uncertainty (Measurement - API 30)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && countFullIsbUnc > 0) {
                     uncSb.append("Avg Full inter-signal bias Uncertainty(ns): ").append(String.format(Locale.US, "%.1f", sumFullIsbUnc / countFullIsbUnc)).append("\n");
                }

                // 12. Avg Sat inter-signal bias Uncertainty (Measurement - API 30)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && countSatIsbUnc > 0) {
                     uncSb.append("Avg Sat inter-signal bias Uncertainty(ns): ").append(String.format(Locale.US, "%.1f", sumSatIsbUnc / countSatIsbUnc)).append("\n");
                }

                contentUncertainty.setText(uncSb.toString());
            }

            private String getStateString(int state) {
                StringBuilder sb = new StringBuilder();
                appendFlag(sb, state, GnssMeasurement.STATE_CODE_LOCK, "CODE_LOCK");
                appendFlag(sb, state, GnssMeasurement.STATE_BIT_SYNC, "BIT_SYNC");
                appendFlag(sb, state, GnssMeasurement.STATE_SUBFRAME_SYNC, "SUBFRAME_SYNC");
                appendFlag(sb, state, GnssMeasurement.STATE_TOW_DECODED, "TOW_DECODED");
                appendFlag(sb, state, GnssMeasurement.STATE_MSEC_AMBIGUOUS, "MSEC_AMBIGUOUS");
                appendFlag(sb, state, GnssMeasurement.STATE_SYMBOL_SYNC, "SYMBOL_SYNC");
                appendFlag(sb, state, GnssMeasurement.STATE_GLO_STRING_SYNC, "GLO_STR_SYNC");
                appendFlag(sb, state, GnssMeasurement.STATE_GLO_TOD_DECODED, "GLO_TOD_DECODED");
                return sb.toString();
            }

            private void appendFlag(StringBuilder sb, int state, int flag, String name) {
                if ((state & flag) != 0) {
                    sb.append("<font color='#00FF00'>").append(name).append("</font> ");
                }
            }
        }
    }

    private static class MeasurementGroup {
        String title;
        List<GnssMeasurement> items;
        GnssClock clock;
        MeasurementGroup(String t, List<GnssMeasurement> i, GnssClock c) {
            title = t; items = i; clock = c;
        }
    }
}
