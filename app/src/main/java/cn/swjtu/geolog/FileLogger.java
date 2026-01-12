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
import android.content.Intent;
import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.GnssStatus;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import cn.swjtu.geolog.LoggerFragment.UIFragmentComponent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A GNSS logger to store information to a file. */
public class FileLogger implements MeasurementListener {

  private static final String TAG = "FileLogger";
  private static final String FILE_PREFIX = "gnss_log";
  private static final String ERROR_WRITING_FILE = "Problem writing to file.";
  private static final String COMMENT_START = "# ";
  private static final char RECORD_DELIMITER = ',';
  private static final String VERSION_TAG = "Version: ";

  private final RinexLogger mRinexLogger;

  private final Context mContext;

  private final Object mFileLock = new Object();
  private BufferedWriter mFileWriter;
  private File mFile;

  private UIFragmentComponent mUiComponent;

  public synchronized UIFragmentComponent getUiComponent() {
    return mUiComponent;
  }

  public synchronized void setUiComponent(UIFragmentComponent value) {
    mUiComponent = value;
  }

  public FileLogger(Context context) {
    this.mContext = context;
    this.mRinexLogger = new RinexLogger(context);
  }

  /** Start a new file logging process. */
  public void startNewLog() {
    synchronized (mFileLock) {
      // 创建原始 GNSS 数据文件
      File baseDirectory;
      String state = Environment.getExternalStorageState();
      // 尝试在根目录下创建 GeoLog 文件夹 (需要权限)
      if (Environment.MEDIA_MOUNTED.equals(state)) {
        baseDirectory = new File(Environment.getExternalStorageDirectory(), "GeoLog");
        if (!baseDirectory.exists()) {
             baseDirectory.mkdirs();
        }
        if (!baseDirectory.exists() || !baseDirectory.canWrite()) {
             // 回退到只有应用自己能访问的目录
             baseDirectory = new File(mContext.getExternalFilesDir(null), FILE_PREFIX);
        }

        if (!baseDirectory.exists() && !baseDirectory.mkdirs()) {
          logError("Failed to create directory: " + baseDirectory.getAbsolutePath());
          return;
        }
      } else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
        logError("Cannot write to external storage.");
        return;
      } else {
        logError("Cannot read external storage.");
        return;
      }

      SimpleDateFormat formatter = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US);
      Date now = new Date();
      String fileName = String.format("%s_%s.txt", FILE_PREFIX, formatter.format(now));
      File currentFile = new File(baseDirectory, fileName);
      String currentFilePath = currentFile.getAbsolutePath();
      BufferedWriter currentFileWriter;
      try {
        currentFileWriter = new BufferedWriter(new FileWriter(currentFile));
      } catch (IOException e) {
        logException("Could not open file: " + currentFilePath, e);
        return;
      }

      // initialize the contents of the file
      try {
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.write("Header Description:");
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.write(VERSION_TAG);
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        String fileVersion =
                mContext.getString(R.string.app_version)
                        + " Platform: "
                        + Build.VERSION.RELEASE
                        + " "
                        + "Manufacturer: "
                        + manufacturer
                        + " "
                        + "Model: "
                        + model;
        currentFileWriter.write(fileVersion);
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.write(
                "Raw,utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,"
                        + "BiasNanos,BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond,"
                        + "HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos,"
                        + "ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond,"
                        + "PseudorangeRateUncertaintyMetersPerSecond,"
                        + "AccumulatedDeltaRangeState,AccumulatedDeltaRangeMeters,"
                        + "AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles,"
                        + "CarrierPhase,CarrierPhaseUncertainty,MultipathIndicator,SnrInDb,"
                        + "ConstellationType,AgcDb,BasebandCn0DbHz,FullInterSignalBiasNanos,"
                        + "FullInterSignalBiasUncertaintyNanos,SatelliteInterSignalBiasNanos,"
                        + "SatelliteInterSignalBiasUncertaintyNanos,CodeType,ChipsetElapsedRealtimeNanos,"
                        + "IsFullTracking,SvPositionEcefXMeters,SvPositionEcefYMeters,"
                        + "SvPositionEcefZMeters,SvVelocityEcefXMetersPerSecond,"
                        + "SvVelocityEcefYMetersPerSecond,SvVelocityEcefZMetersPerSecond,SvClockBiasMeters,"
                        + "SvClockDriftMetersPerSecond,KlobucharAlpha0,KlobucharAlpha1,"
                        + "KlobucharAlpha2,KlobucharAlpha3,KlobucharBeta0,KlobucharBeta1,"
                        + "KlobucharBeta2,KlobucharBeta3");
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.write("Nav,Svid,Type,Status,MessageId,Sub-messageId,Data(Bytes)");
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.newLine();
      } catch (IOException e) {
        logException("Count not initialize file: " + currentFilePath, e);
        return;
      }

      if (mFileWriter != null) {
        try {
          mFileWriter.close();
        } catch (IOException e) {
          logException("Unable to close all file streams.", e);
          return;
        }
      }

      if (isRinexLogging) {
          mRinexLogger.stopLog();
          isRinexLogging = false;
      }

      mFile = currentFile;
      mFileWriter = currentFileWriter;
      Toast.makeText(mContext, "File opened: " + currentFilePath, Toast.LENGTH_SHORT).show();

      if (mRinexConversionEnabled) {
          // Pass prefix and date to RinexLogger to handle new naming convention: new_FILEPREFIX_YYYY... .yyO
          // Note: RinexLogger now expects "base filename" (without extension) or prefix.
          // The Python script used: new_{basename}.{yy}o
          // The basename here is fileName (e.g., gnss_log_2026_01_09...) without extension.
          String currentBaseName = fileName.replace(".txt", "");
          mRinexLogger.startNewLog(baseDirectory, currentBaseName, now);
          isRinexLogging = true;
          Toast.makeText(mContext, "RINEX log started", Toast.LENGTH_SHORT).show();
      }
    }
  }

  /**
   * Send the current log via email or other options selected from a pop menu shown to the user. A
   * new log is started when calling this function.
   */
  public void send() {
    if (mFile == null) {
      return;
    }

    if (mFileWriter != null) {
      try {
        mFileWriter.flush();
        mFileWriter.close();
        mFileWriter = null;
      } catch (IOException e) {
        logException("Unable to close all file streams.", e);
        return;
      }
    }

    if (isRinexLogging) {
        mRinexLogger.stopLog();
        isRinexLogging = false;
    }

    Intent emailIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);

    emailIntent.setType("*/*");
    emailIntent.putExtra(Intent.EXTRA_SUBJECT, "SensorLog");
    emailIntent.putExtra(Intent.EXTRA_TEXT, "");

    // 附加原始 GNSS 数据文件
    List<Uri> uris = new ArrayList<>();
    try {
      Uri fileURI =
              FileProvider.getUriForFile(mContext, BuildConfig.APPLICATION_ID + ".provider", mFile);
      uris.add(fileURI);
    } catch (IllegalArgumentException e) {
      logException("Error getting file URI for GNSS log", e);
      return;
    }

    // Attach RINEX file if available
    File rinexFile = mRinexLogger.getFile();
    if (rinexFile != null && rinexFile.exists()) {
      try {
        Uri rinexFileURI = FileProvider.getUriForFile(mContext, BuildConfig.APPLICATION_ID + ".provider", rinexFile);
        uris.add(rinexFileURI);
      } catch (IllegalArgumentException e) {
        logException("Error getting file URI for RINEX log", e);
        // Continue sending even if RINEX attach fails
      }
    }

    emailIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));

    // 检查是否有能够处理这个 Intent 的应用
    if (emailIntent.resolveActivity(mContext.getPackageManager()) != null) {
      getUiComponent().startActivity(Intent.createChooser(emailIntent, "Send log.."));
    } else {
      logError("没有找到处理邮件的应用！请安装一个邮件应用。");
      Toast.makeText(mContext, "没有找到处理邮件的应用！", Toast.LENGTH_LONG).show();
    }
  }

  // Flag to store user preference (from Settings)
  private boolean mRinexConversionEnabled = false;

  // Actual state of RINEX logging (during active logging session)
  private boolean isRinexLogging = false;

  /*  控制 RINEX 记录的开启和关闭*/

  // Called by SettingsFragment switch
  public void startRinexLogging() {
    mRinexConversionEnabled = true;
  }

  // Called by SettingsFragment switch
  public void stopRinexLogging() {
    mRinexConversionEnabled = false;
  }

  @Override
  public void onProviderEnabled(String provider) {}

  @Override
  public void onProviderDisabled(String provider) {}


  @Override
  public void onLocationChanged(Location location) {
    synchronized (mFileLock) {
      if (mFileWriter == null) {
        return;
      }
      String locationStream =
              String.format(
                      Locale.US,
                      "Fix,%s,%f,%f,%f,%f,%f,%d",
                      location.getProvider(),
                      location.getLatitude(),
                      location.getLongitude(),
                      location.getAltitude(),
                      location.getSpeed(),
                      location.getAccuracy(),
                      location.getTime());
      try {
        mFileWriter.write(locationStream);
        mFileWriter.newLine();
      } catch (IOException e) {
        logException(ERROR_WRITING_FILE, e);
      }

      // Update RinexLogger
      if (isRinexLogging) {
        mRinexLogger.updateLocation(location);
      }
    }
  }

  @Override
  public void onLocationStatusChanged(String provider, int status, Bundle extras) {}

  @Override
  public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
    synchronized (mFileLock) {
      if (mFileWriter == null) {
        return;
      }
      GnssClock gnssClock = event.getClock();
      for (GnssMeasurement measurement : event.getMeasurements()) {
        try {
          writeGnssMeasurementToFile(gnssClock, measurement);
        } catch (IOException e) {
          logException(ERROR_WRITING_FILE, e);
        }
      }

      // Update RinexLogger
      if (isRinexLogging) {
        mRinexLogger.processGnssMeasurements(event);
      }
    }
  }

  @Override
  public void onGnssMeasurementsStatusChanged(int status) {}

  @Override
  public void onGnssNavigationMessageReceived(GnssNavigationMessage navigationMessage) {
    synchronized (mFileLock) {
      if (mFileWriter == null) {
        return;
      }
      StringBuilder builder = new StringBuilder("Nav");
      builder.append(RECORD_DELIMITER);
      builder.append(navigationMessage.getSvid());
      builder.append(RECORD_DELIMITER);
      builder.append(navigationMessage.getType());
      builder.append(RECORD_DELIMITER);

      int status = navigationMessage.getStatus();
      builder.append(status);
      builder.append(RECORD_DELIMITER);
      builder.append(navigationMessage.getMessageId());
      builder.append(RECORD_DELIMITER);
      builder.append(navigationMessage.getSubmessageId());
      byte[] data = navigationMessage.getData();
      for (byte word : data) {
        builder.append(RECORD_DELIMITER);
        builder.append(word);
      }
      try {
        mFileWriter.write(builder.toString());
        mFileWriter.newLine();
      } catch (IOException e) {
        logException(ERROR_WRITING_FILE, e);
      }
    }
  }

  @Override
  public void onGnssNavigationMessageStatusChanged(int status) {}

  @Override
  public void onGnssStatusChanged(GnssStatus gnssStatus) {}

  @Override
  public void onNmeaReceived(long timestamp, String s) {
    synchronized (mFileLock) {
      if (mFileWriter == null) {
        return;
      }
      String nmeaStream = String.format(Locale.US, "NMEA,%s,%d", s.trim(), timestamp);
      try {
        mFileWriter.write(nmeaStream);
        mFileWriter.newLine();
      } catch (IOException e) {
        logException(ERROR_WRITING_FILE, e);
      }
    }
  }

  @Override
  public void onListenerRegistration(String listener, boolean result) {}

  @Override
  public void onTTFFReceived(long l) {}

  private void writeGnssMeasurementToFile(GnssClock clock, GnssMeasurement measurement)
          throws IOException {
    String utcTimeMillisStr = "";

// TOW 已解算（前提）
    boolean towDecoded =
            (measurement.getState() & GnssMeasurement.STATE_TOW_DECODED) != 0;

    if (towDecoded
            && clock.hasFullBiasNanos()
            && clock.hasBiasNanos()
            && clock.hasLeapSecond()) {

      long gpsTimeNanos =
              clock.getTimeNanos()
                      - clock.getFullBiasNanos()
                      - (long) clock.getBiasNanos();

      long utcTimeNanos =
              gpsTimeNanos
                      - clock.getLeapSecond() * 1_000_000_000L;

      utcTimeMillisStr = String.valueOf(utcTimeNanos / 1_000_000L);
    } else {
      // GNSS 时间尚未物理成立 留空
      utcTimeMillisStr = "";
    }


    String chipsetElapsedRealtimeNanos = "";
    // ChipsetElapsedRealtimeNanos
    // We use SystemClock.elapsedRealtimeNanos() to represent the time since boot in nanoseconds
    // at the time of data processing. This is a standard substitution for Chipset Elapsed Time
    // when the specific GNSS chipset time aligned to elapsed realtime is not directly exposed.
    chipsetElapsedRealtimeNanos = String.valueOf(SystemClock.elapsedRealtimeNanos());

    String basebandCn0DbHz = "";
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasBasebandCn0DbHz()) {
      basebandCn0DbHz = String.valueOf(measurement.getBasebandCn0DbHz());
    }

    String fullInterSignalBiasNanos = "";
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasFullInterSignalBiasNanos()) {
      fullInterSignalBiasNanos = String.valueOf(measurement.getFullInterSignalBiasNanos());
    } else {
        fullInterSignalBiasNanos = "-1"; // Invalid value
    }

    String fullInterSignalBiasUncertaintyNanos = "";
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && measurement.hasFullInterSignalBiasUncertaintyNanos()) {
      fullInterSignalBiasUncertaintyNanos =
              String.valueOf(measurement.getFullInterSignalBiasUncertaintyNanos());
    } else {
        fullInterSignalBiasUncertaintyNanos = "-1";
    }

    String satelliteInterSignalBiasNanos = "";
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && measurement.hasSatelliteInterSignalBiasNanos()) {
      satelliteInterSignalBiasNanos = String.valueOf(measurement.getSatelliteInterSignalBiasNanos());
    } else {
        satelliteInterSignalBiasNanos = "-1";
    }

    String satelliteInterSignalBiasUncertaintyNanos = "";
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && measurement.hasSatelliteInterSignalBiasUncertaintyNanos()) {
      satelliteInterSignalBiasUncertaintyNanos =
              String.valueOf(measurement.getSatelliteInterSignalBiasUncertaintyNanos());
    } else {
        satelliteInterSignalBiasUncertaintyNanos = "-1";
    }

    String codeType = "";
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && measurement.hasCodeType()) {
      codeType = measurement.getCodeType();
    }

    String isFullTrackingStr;

    long state = measurement.getState();

// 1. 码跟踪
    boolean codeLock =
            (state & GnssMeasurement.STATE_CODE_LOCK) != 0;

// 2. 有明确 GNSS 时间（按系统区分）
    boolean timeDecoded =
            (state & GnssMeasurement.STATE_TOW_DECODED) != 0
                    || (state & GnssMeasurement.STATE_GLO_STRING_SYNC) != 0
                    || (state & GnssMeasurement.STATE_GLO_TOD_DECODED) != 0;

// 3. 载波是否可用
    boolean carrierUsable =
            measurement.hasCarrierPhase()
                    && (measurement.getAccumulatedDeltaRangeState()
                    & GnssMeasurement.ADR_STATE_VALID) != 0;

// === 最终 FullTracking ===
    boolean isFullTracking =
            codeLock
                    && timeDecoded
                    && carrierUsable;

    isFullTrackingStr = String.valueOf(isFullTracking);



    String clockStream =
            String.format(
                    "Raw,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
                    utcTimeMillisStr,
                    clock.getTimeNanos(),
                    clock.hasLeapSecond() ? clock.getLeapSecond() : "",
                    clock.hasTimeUncertaintyNanos() ? clock.getTimeUncertaintyNanos() : "",
                    clock.getFullBiasNanos(),
                    clock.hasBiasNanos() ? clock.getBiasNanos() : "",
                    clock.hasBiasUncertaintyNanos() ? clock.getBiasUncertaintyNanos() : "",
                    clock.hasDriftNanosPerSecond() ? clock.getDriftNanosPerSecond() : "",
                    clock.hasDriftUncertaintyNanosPerSecond()
                            ? clock.getDriftUncertaintyNanosPerSecond()
                            : "",
                    clock.getHardwareClockDiscontinuityCount() + ",");
    mFileWriter.write(clockStream);

    // SV Position, Velocity, Clock, Klobuchar - Not available in GnssMeasurement directly
    // Placeholder values used to maintain CSV structure as they are not available from GnssMeasurement
    String measurementStream =
            String.format(
                    "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
                    measurement.getSvid(),
                    measurement.getTimeOffsetNanos(),
                    measurement.getState(),
                    measurement.getReceivedSvTimeNanos(),
                    measurement.getReceivedSvTimeUncertaintyNanos(),
                    measurement.getCn0DbHz(),
                    measurement.getPseudorangeRateMetersPerSecond(),
                    measurement.getPseudorangeRateUncertaintyMetersPerSecond(),
                    measurement.getAccumulatedDeltaRangeState(),
                    measurement.getAccumulatedDeltaRangeMeters(),
                    measurement.getAccumulatedDeltaRangeUncertaintyMeters(),
                    measurement.hasCarrierFrequencyHz() ? measurement.getCarrierFrequencyHz() : "",
                    measurement.hasCarrierCycles() ? measurement.getCarrierCycles() : "",
                    measurement.hasCarrierPhase() ? measurement.getCarrierPhase() : "",
                    measurement.hasCarrierPhaseUncertainty()
                            ? measurement.getCarrierPhaseUncertainty()
                            : "",
                    measurement.getMultipathIndicator(),
                    measurement.hasSnrInDb() ? measurement.getSnrInDb() : "",
                    measurement.getConstellationType(),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            && measurement.hasAutomaticGainControlLevelDb()
                            ? measurement.getAutomaticGainControlLevelDb()
                            : "",
                    basebandCn0DbHz,
                    fullInterSignalBiasNanos,
                    fullInterSignalBiasUncertaintyNanos,
                    satelliteInterSignalBiasNanos,
                    satelliteInterSignalBiasUncertaintyNanos,
                    codeType,
                    chipsetElapsedRealtimeNanos,
                    isFullTracking,
                    "", "", "", // SvPositionEcef
                    "", "", "", // SvVelocityEcef
                    "", "",     // SvClock
                    "", "", "", "", "", "", "", "" // Klobuchar
            );
    mFileWriter.write(measurementStream);
    mFileWriter.newLine();
  }

  private void logException(String errorMessage, Exception e) {
    Log.e(MeasurementProvider.TAG + TAG, errorMessage, e);
    Toast.makeText(mContext, errorMessage, Toast.LENGTH_LONG).show();
  }

  private void logError(String errorMessage) {
    Log.e(MeasurementProvider.TAG + TAG, errorMessage);
    Toast.makeText(mContext, errorMessage, Toast.LENGTH_LONG).show();
  }
}

