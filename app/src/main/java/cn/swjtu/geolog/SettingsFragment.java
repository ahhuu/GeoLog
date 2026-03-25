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

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.PopupWindow;
import android.widget.PopupWindow.OnDismissListener;
import android.widget.Spinner;
import com.google.android.material.switchmaterial.SwitchMaterial;
import android.widget.TextView;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import java.lang.reflect.InvocationTargetException;

/**
 * The UI fragment showing a set of configurable settings for the client to
 * request GPS data.
 */
public class SettingsFragment extends Fragment {

  public static final String TAG = ":SettingsFragment";

  /** Position in the drop down menu of the auto ground truth mode */
  private static int AUTO_GROUND_TRUTH_MODE = 3;

  /**
   * Key in the {@link SharedPreferences} indicating whether auto-scroll has been
   * enabled
   */
  protected static String PREFERENCE_KEY_AUTO_SCROLL = "autoScroll";

  private MeasurementProvider mGpsContainer;
  private HelpDialog helpDialog;

  private FileLogger mFileLogger;

  public void setFileLogger(FileLogger fileLogger) {
    this.mFileLogger = fileLogger;
  }

  /**
   * The {@link RealTimePositionVelocityCalculator} set for receiving the ground
   * truth mode switch
   */
  private RealTimePositionVelocityCalculator mRealTimePositionVelocityCalculator;

  /** User selection of ground truth mode, initially set to be disabled */
  private int mResidualSetting = RealTimePositionVelocityCalculator.RESIDUAL_MODE_DISABLED;

  /** The reference ground truth location by user input. */
  private double[] mFixedReferenceLocation = null;

  /**
   * {@link GroundTruthModeSwitcher} to receive update from AR result broadcast
   */
  private GroundTruthModeSwitcher mModeSwitcher;

  @Override
  public void onAttach(Context context) {
    super.onAttach(context);
    if (context instanceof MainActivity) {
      MainActivity activity = (MainActivity) context;
      if (mGpsContainer == null) {
        mGpsContainer = activity.getMeasurementProvider();
      }
      if (mRealTimePositionVelocityCalculator == null) {
        mRealTimePositionVelocityCalculator = activity.getRealTimePositionVelocityCalculator();
      }
      if (mFileLogger == null) {
        mFileLogger = activity.getFileLogger();
      }
      if (mModeSwitcher == null) {
        mModeSwitcher = activity;
      }
    }
  }

  public void setGpsContainer(MeasurementProvider value) {
    mGpsContainer = value;
  }

  /** Set up {@link MainActivity} to receive update from AR result broadcast */
  public void setAutoModeSwitcher(GroundTruthModeSwitcher modeSwitcher) {
    mModeSwitcher = modeSwitcher;
  }

  /**
   * Set up {@code RealTimePositionVelocityCalculator} for receiving changes in
   * ground truth mode
   */
  public void setRealTimePositionVelocityCalculator(
      RealTimePositionVelocityCalculator realTimePositionVelocityCalculator) {
    mRealTimePositionVelocityCalculator = realTimePositionVelocityCalculator;
  }

  @Override
  public View onCreateView(
      LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    final View view = inflater.inflate(R.layout.fragment_main, container, false /* attachToRoot */);

    if (mGpsContainer == null && getActivity() instanceof MainActivity) {
      mGpsContainer = ((MainActivity) getActivity()).getMeasurementProvider();
    }
    final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getActivity());

    final SwitchMaterial registerLocation = (SwitchMaterial) view.findViewById(R.id.register_location);
    final TextView registerLocationLabel = (TextView) view.findViewById(R.id.register_location_label);
    // set the switch to OFF
    registerLocation.setChecked(false);
    registerLocationLabel.setText("Switch is OFF");
    registerLocation.setOnCheckedChangeListener(
        new OnCheckedChangeListener() {

          @Override
          public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (mGpsContainer == null) {
              if (getActivity() instanceof MainActivity) {
                mGpsContainer = ((MainActivity) getActivity()).getMeasurementProvider();
              }
              if (mGpsContainer == null) {
                Toast.makeText(getContext(), "GPS Container not initialized", Toast.LENGTH_SHORT).show();
                return;
              }
            }

            if (isChecked) {
              mGpsContainer.registerLocation();
              mGpsContainer.registerFusedLocation();
              registerLocationLabel.setText("Switch is ON");
            } else {
              mGpsContainer.unregisterLocation();
              mGpsContainer.unRegisterFusedLocation();
              registerLocationLabel.setText("Switch is OFF");
            }
          }
        });

    final SwitchMaterial registerMeasurements = (SwitchMaterial) view.findViewById(R.id.register_measurements);
    final TextView registerMeasurementsLabel = (TextView) view.findViewById(R.id.register_measurement_label);
    // set the switch to OFF
    registerMeasurements.setChecked(false);
    registerMeasurementsLabel.setText("Switch is OFF");
    registerMeasurements.setOnCheckedChangeListener(
        new OnCheckedChangeListener() {

          @Override
          public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (mGpsContainer == null) {
              if (getActivity() instanceof MainActivity) {
                mGpsContainer = ((MainActivity) getActivity()).getMeasurementProvider();
              }
              if (mGpsContainer == null)
                return;
            }

            if (isChecked) {
              mGpsContainer.registerMeasurements();
              registerMeasurementsLabel.setText("Switch is ON");
            } else {
              mGpsContainer.unregisterMeasurements();
              registerMeasurementsLabel.setText("Switch is OFF");
            }
          }
        });

    final SwitchMaterial registerNavigation = (SwitchMaterial) view.findViewById(R.id.register_navigation);
    final TextView registerNavigationLabel = (TextView) view.findViewById(R.id.register_navigation_label);
    // set the switch to OFF
    registerNavigation.setChecked(false);
    registerNavigationLabel.setText("Switch is OFF");
    registerNavigation.setOnCheckedChangeListener(
        new OnCheckedChangeListener() {

          @Override
          public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (mGpsContainer == null) {
              if (getActivity() instanceof MainActivity) {
                mGpsContainer = ((MainActivity) getActivity()).getMeasurementProvider();
              }
              if (mGpsContainer == null)
                return;
            }

            if (isChecked) {
              mGpsContainer.registerNavigation();
              registerNavigationLabel.setText("Switch is ON");
            } else {
              mGpsContainer.unregisterNavigation();
              registerNavigationLabel.setText("Switch is OFF");
            }
          }
        });

    final SwitchMaterial registerGpsStatus = (SwitchMaterial) view.findViewById(R.id.register_status);
    final TextView registerGpsStatusLabel = (TextView) view.findViewById(R.id.register_status_label);
    // set the switch to OFF
    registerGpsStatus.setChecked(false);
    registerGpsStatusLabel.setText("Switch is OFF");
    registerGpsStatus.setOnCheckedChangeListener(
        new OnCheckedChangeListener() {

          @Override
          public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (mGpsContainer == null) {
              if (getActivity() instanceof MainActivity) {
                mGpsContainer = ((MainActivity) getActivity()).getMeasurementProvider();
              }
              if (mGpsContainer == null)
                return;
            }

            if (isChecked) {
              mGpsContainer.registerGnssStatus();
              registerGpsStatusLabel.setText("Switch is ON");
            } else {
              mGpsContainer.unregisterGpsStatus();
              registerGpsStatusLabel.setText("Switch is OFF");
            }
          }
        });

    final SwitchMaterial registerNmea = (SwitchMaterial) view.findViewById(R.id.register_nmea);
    final TextView registerNmeaLabel = (TextView) view.findViewById(R.id.register_nmea_label);
    // set the switch to OFF
    registerNmea.setChecked(false);
    registerNmeaLabel.setText("Switch is OFF");
    registerNmea.setOnCheckedChangeListener(
        new OnCheckedChangeListener() {

          @Override
          public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (mGpsContainer == null) {
              if (getActivity() instanceof MainActivity) {
                mGpsContainer = ((MainActivity) getActivity()).getMeasurementProvider();
              }
              if (mGpsContainer == null)
                return;
            }

            if (isChecked) {
              mGpsContainer.registerNmea();
              registerNmeaLabel.setText("Switch is ON");
            } else {
              mGpsContainer.unregisterNmea();
              registerNmeaLabel.setText("Switch is OFF");
            }
          }
        });
    final SwitchMaterial autoScroll = (SwitchMaterial) view.findViewById(R.id.auto_scroll_on);
    final TextView turnOnAutoScroll = (TextView) view.findViewById(R.id.turn_on_auto_scroll);
    turnOnAutoScroll.setText("Switch is OFF");
    autoScroll.setOnCheckedChangeListener(
        new OnCheckedChangeListener() {
          @Override
          public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            Editor editor = sharedPreferences.edit();
            if (isChecked) {
              editor.putBoolean(PREFERENCE_KEY_AUTO_SCROLL, true);
              editor.apply();
              turnOnAutoScroll.setText("Switch is ON");
            } else {
              editor.putBoolean(PREFERENCE_KEY_AUTO_SCROLL, false);
              editor.apply();
              turnOnAutoScroll.setText("Switch is OFF");
            }
          }
        });

    final SwitchMaterial registerRinex = view.findViewById(R.id.register_rinex);
    final TextView registerRinexLabel = view.findViewById(R.id.register_rinex_label);
    // set the switch to OFF
    registerRinex.setChecked(false);
    registerRinexLabel.setText("Switch is OFF");
    registerRinex.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (isChecked) {
          mFileLogger.startRinexLogging();
          registerRinexLabel.setText("Switch is ON");
          LoggerFragment.UIFragmentComponent uiComponent = ((MainActivity) getActivity()).getUIFragmentComponent();
          if (uiComponent != null) {
            uiComponent.logTextFragment("RINEX", "RINEX logging started", android.graphics.Color.GREEN);
          }
        } else {
          mFileLogger.stopRinexLogging();
          registerRinexLabel.setText("Switch is OFF");
          LoggerFragment.UIFragmentComponent uiComponent = ((MainActivity) getActivity()).getUIFragmentComponent();
          if (uiComponent != null) {
            uiComponent.logTextFragment("RINEX", "RINEX logging stopped", android.graphics.Color.RED);
          }
        }
      }
    });

    final SwitchMaterial residualPlotSwitch = (SwitchMaterial) view.findViewById(R.id.residual_plot_enabled);
    final TextView turnOnResidual = (TextView) view.findViewById(R.id.turn_on_residual_plot);
    turnOnResidual.setText("Switch is OFF");
    residualPlotSwitch.setOnCheckedChangeListener(
        new OnCheckedChangeListener() {
          @Override
          public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (isChecked) {

              LayoutInflater inflater = (LayoutInflater) getActivity()
                  .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
              View layout = inflater.inflate(
                  R.layout.pop_up_window, (ViewGroup) getActivity().findViewById(R.id.pop));

              // Find UI elements in pop up window
              final Spinner residualSpinner = layout.findViewById(R.id.residual_spinner);
              Button buttonOk = layout.findViewById(R.id.popup_button_ok);
              Button buttonCancel = layout.findViewById(R.id.popup_button_cancel);
              final TextView longitudeInput = layout.findViewById(R.id.longitude_input);
              final TextView latitudeInput = layout.findViewById(R.id.latitude_input);
              final TextView altitudeInput = layout.findViewById(R.id.altitude_input);

              // Set up pop up window attributes
              final PopupWindow popupWindow = new PopupWindow(layout, LayoutParams.WRAP_CONTENT,
                  LayoutParams.WRAP_CONTENT);
              popupWindow.setOutsideTouchable(false);
              popupWindow.showAtLocation(
                  view.findViewById(R.id.setting_root), Gravity.CENTER, 0, 0);
              View container = (View) popupWindow.getContentView().getParent();
              WindowManager wm = (WindowManager) getActivity().getSystemService(Context.WINDOW_SERVICE);
              WindowManager.LayoutParams params = (WindowManager.LayoutParams) container.getLayoutParams();
              params.flags = WindowManager.LayoutParams.FLAG_DIM_BEHIND;
              params.dimAmount = 0.5f;
              wm.updateViewLayout(container, params);
              mResidualSetting = RealTimePositionVelocityCalculator.RESIDUAL_MODE_DISABLED;
              // When the window is dismissed same as cancel
              popupWindow.setOnDismissListener(
                  new OnDismissListener() {
                    @Override
                    public void onDismiss() {
                      if (mResidualSetting == RealTimePositionVelocityCalculator.RESIDUAL_MODE_DISABLED) {
                        residualPlotSwitch.setChecked(false);
                      } else {
                        mRealTimePositionVelocityCalculator.setResidualPlotMode(
                            mResidualSetting, mFixedReferenceLocation);
                        turnOnResidual.setText("Switch is ON");
                      }
                    }
                  });

              buttonCancel.setOnClickListener(
                  new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                      popupWindow.dismiss();
                    }
                  });

              // Button handler to dismiss the window and store settings
              buttonOk.setOnClickListener(
                  new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                      double longitudeDegrees = longitudeInput.getText().toString().equals("")
                          ? Double.NaN
                          : Double.parseDouble(longitudeInput.getText().toString());
                      double latitudeDegrees = latitudeInput.getText().toString().equals("")
                          ? Double.NaN
                          : Double.parseDouble(latitudeInput.getText().toString());
                      double altitudeMeters = altitudeInput.getText().toString().equals("")
                          ? Double.NaN
                          : Double.parseDouble(altitudeInput.getText().toString());
                      mFixedReferenceLocation = new double[] { latitudeDegrees, longitudeDegrees, altitudeMeters };
                      mResidualSetting = residualSpinner.getSelectedItemPosition();

                      // If user select auto, we need to put moving first and turn on AR updates
                      if (mResidualSetting == AUTO_GROUND_TRUTH_MODE) {
                        mResidualSetting = RealTimePositionVelocityCalculator.RESIDUAL_MODE_MOVING;
                        mModeSwitcher.setAutoSwitchGroundTruthModeEnabled(true);
                      }
                      popupWindow.dismiss();
                    }
                  });

            } else {
              mModeSwitcher.setAutoSwitchGroundTruthModeEnabled(false);
              mRealTimePositionVelocityCalculator.setResidualPlotMode(
                  RealTimePositionVelocityCalculator.RESIDUAL_MODE_DISABLED,
                  mFixedReferenceLocation);
              turnOnResidual.setText("Switch is OFF");
            }
          }
        });
    Button help = (Button) view.findViewById(R.id.help);
    helpDialog = new HelpDialog(getContext());
    helpDialog.setTitle("Help contents");
    helpDialog.create();

    help.setOnClickListener(
        new OnClickListener() {
          @Override
          public void onClick(View view) {
            helpDialog.show();
          }
        });

    Button exit = (Button) view.findViewById(R.id.exit);
    exit.setOnClickListener(
        new OnClickListener() {
          @Override
          public void onClick(View view) {
            getActivity().finishAffinity();
          }
        });

    final SwitchMaterial keepScreenOn = (SwitchMaterial) view.findViewById(R.id.keep_screen_on);
    final TextView keepScreenOnLabel = (TextView) view.findViewById(R.id.keep_screen_on_label);
    boolean isKeepScreenOn = getActivity().getWindow().getAttributes().flags != 0
        && (getActivity().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
    keepScreenOn.setChecked(isKeepScreenOn);
    keepScreenOnLabel.setText(isKeepScreenOn ? "Switch is ON" : "Switch is OFF");
    keepScreenOn.setOnCheckedChangeListener(new OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (isChecked) {
          getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          keepScreenOnLabel.setText("Switch is ON");
        } else {
          getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          keepScreenOnLabel.setText("Switch is OFF");
        }
      }
    });

    final SwitchMaterial autoDim = (SwitchMaterial) view.findViewById(R.id.auto_dim);
    final TextView autoDimLabel = (TextView) view.findViewById(R.id.auto_dim_label);
    boolean isAutoDim = sharedPreferences.getBoolean("auto_dim_during_logging", false);
    autoDim.setChecked(isAutoDim);
    autoDimLabel.setText(isAutoDim ? "Switch is ON" : "Switch is OFF");
    autoDim.setOnCheckedChangeListener(new OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        sharedPreferences.edit().putBoolean("auto_dim_during_logging", isChecked).apply();
        autoDimLabel.setText(isChecked ? "Switch is ON" : "Switch is OFF");
      }
    });

    final EditText stationNameInput = view.findViewById(R.id.rinex_station_name_input);
    final EditText markerNameInput = view.findViewById(R.id.rinex_marker_name_input);
    final EditText markerNumberInput = view.findViewById(R.id.rinex_marker_number_input);
    final Spinner markerTypeSpinner = view.findViewById(R.id.rinex_marker_type_spinner);
    final EditText observerInput = view.findViewById(R.id.rinex_observer_input);
    final EditText agencyInput = view.findViewById(R.id.rinex_agency_input);
    final EditText receiverNumberInput = view.findViewById(R.id.rinex_receiver_number_input);
    final EditText receiverTypeInput = view.findViewById(R.id.rinex_receiver_type_input);
    final EditText receiverVersionInput = view.findViewById(R.id.rinex_receiver_version_input);
    final EditText antennaNumberInput = view.findViewById(R.id.rinex_antenna_number_input);
    final EditText antennaTypeInput = view.findViewById(R.id.rinex_antenna_type_input);
    final EditText antennaDeltaHInput = view.findViewById(R.id.rinex_antenna_delta_h_input);
    final EditText antennaDeltaEInput = view.findViewById(R.id.rinex_antenna_delta_e_input);
    final EditText antennaDeltaNInput = view.findViewById(R.id.rinex_antenna_delta_n_input);
    Button saveRinexSettings = view.findViewById(R.id.rinex_save_settings);

    stationNameInput.setText(sharedPreferences.getString(FileLogger.PREF_RINEX_STATION_NAME, "GNSS00GEO"));
    markerNameInput.setText(sharedPreferences.getString(FileLogger.PREF_RINEX_MARKER_NAME, "GeoLog"));
    markerNumberInput.setText(sharedPreferences.getString(FileLogger.PREF_RINEX_MARKER_NUMBER, "Unknown"));
    String markerType = sharedPreferences.getString(FileLogger.PREF_RINEX_MARKER_TYPE, "GEODETIC");
    int markerTypeIndex = getSpinnerPosition(markerTypeSpinner, markerType);
    if (markerTypeIndex >= 0) {
      markerTypeSpinner.setSelection(markerTypeIndex);
    }
    observerInput.setText(sharedPreferences.getString(FileLogger.PREF_RINEX_OBSERVER, "SWJTU"));
    agencyInput.setText(sharedPreferences.getString(FileLogger.PREF_RINEX_AGENCY, "SWJTU"));
    receiverNumberInput.setText(sharedPreferences.getString(FileLogger.PREF_RINEX_RECEIVER_NUMBER, "Unknown"));
    receiverTypeInput.setText(sharedPreferences.getString(
      FileLogger.PREF_RINEX_RECEIVER_TYPE,
      Build.MANUFACTURER + " " + Build.MODEL));
    receiverVersionInput.setText(sharedPreferences.getString(FileLogger.PREF_RINEX_RECEIVER_VERSION, Build.VERSION.RELEASE));
    antennaNumberInput.setText(sharedPreferences.getString(FileLogger.PREF_RINEX_ANTENNA_NUMBER, "unknown"));
    antennaTypeInput.setText(sharedPreferences.getString(FileLogger.PREF_RINEX_ANTENNA_TYPE, "unknown"));
    antennaDeltaHInput.setText(sharedPreferences.getString(FileLogger.PREF_RINEX_ANTENNA_DELTA_H, "0.0000"));
    antennaDeltaEInput.setText(sharedPreferences.getString(FileLogger.PREF_RINEX_ANTENNA_DELTA_E, "0.0000"));
    antennaDeltaNInput.setText(sharedPreferences.getString(FileLogger.PREF_RINEX_ANTENNA_DELTA_N, "0.0000"));

    saveRinexSettings.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        String deltaH = antennaDeltaHInput.getText().toString().trim();
        String deltaE = antennaDeltaEInput.getText().toString().trim();
        String deltaN = antennaDeltaNInput.getText().toString().trim();

        if (!isValidDouble(deltaH) || !isValidDouble(deltaE) || !isValidDouble(deltaN)) {
          Toast.makeText(getContext(), "Antenna Delta H/E/N must be numeric", Toast.LENGTH_SHORT).show();
          return;
        }

        sharedPreferences.edit()
            .putString(FileLogger.PREF_RINEX_STATION_NAME, stationNameInput.getText().toString().trim())
            .putString(FileLogger.PREF_RINEX_MARKER_NAME, markerNameInput.getText().toString().trim())
            .putString(FileLogger.PREF_RINEX_MARKER_NUMBER, markerNumberInput.getText().toString().trim())
            .putString(FileLogger.PREF_RINEX_MARKER_TYPE, markerTypeSpinner.getSelectedItem().toString())
            .putString(FileLogger.PREF_RINEX_OBSERVER, observerInput.getText().toString().trim())
            .putString(FileLogger.PREF_RINEX_AGENCY, agencyInput.getText().toString().trim())
            .putString(FileLogger.PREF_RINEX_RECEIVER_NUMBER, receiverNumberInput.getText().toString().trim())
            .putString(FileLogger.PREF_RINEX_RECEIVER_TYPE, receiverTypeInput.getText().toString().trim())
            .putString(FileLogger.PREF_RINEX_RECEIVER_VERSION, receiverVersionInput.getText().toString().trim())
            .putString(FileLogger.PREF_RINEX_ANTENNA_NUMBER, antennaNumberInput.getText().toString().trim())
            .putString(FileLogger.PREF_RINEX_ANTENNA_TYPE, antennaTypeInput.getText().toString().trim())
            .putString(FileLogger.PREF_RINEX_ANTENNA_DELTA_H, deltaH)
            .putString(FileLogger.PREF_RINEX_ANTENNA_DELTA_E, deltaE)
            .putString(FileLogger.PREF_RINEX_ANTENNA_DELTA_N, deltaN)
            .apply();

        if (mFileLogger != null) {
          mFileLogger.refreshRinexSettings();
        }
        Toast.makeText(getContext(), "RINEX settings saved", Toast.LENGTH_SHORT).show();
      }
    });

    TextView appVersion = view.findViewById(R.id.app_version_text);
    if (appVersion != null) {
      try {
        String v = getActivity().getPackageManager().getPackageInfo(getActivity().getPackageName(), 0).versionName;
        appVersion.setText("App Version: " + v);
      } catch (Exception e) {
        appVersion.setText("App Version: Unknown");
      }
    }

    return view;
  }

  private void logException(String errorMessage, Exception e) {
    Log.e(MeasurementProvider.TAG + TAG, errorMessage, e);
    Toast.makeText(getContext(), errorMessage, Toast.LENGTH_LONG).show();
  }

  private int getSpinnerPosition(Spinner spinner, String value) {
    if (spinner == null || value == null) {
      return -1;
    }
    for (int i = 0; i < spinner.getCount(); i++) {
      if (value.equalsIgnoreCase(String.valueOf(spinner.getItemAtPosition(i)))) {
        return i;
      }
    }
    return -1;
  }

  private boolean isValidDouble(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    try {
      Double.parseDouble(value);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
