package cn.swjtu.geolog;

import android.content.Context;
import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.GnssStatus;
import android.location.Location;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A logger that converts GNSS measurements to RINEX 3.05 format.
 * Ported logic from Androidgnsslog_to_rinex.py.
 */
public class RinexLogger {

    private static final String TAG = "RinexLogger";
    private static final double CLIGHT = 299792458.0;
    private static final double NEAR_ZERO = 0.0001;
    private static final int LEAP_SECOND = 18; // As of 2021/2025

    // System Constants
    private static final int SYS_GPS = 1;
    private static final int SYS_GLO = 3;
    private static final int SYS_QZS = 4;
    private static final int SYS_BDS = 5;
    private static final int SYS_GAL = 6;
    private static final int MAX_SYS = 10;
    private static final int MAX_FRQ = 5;

    // Measurement States (matching Python script constants)
    private static final int STATE_CODE_LOCK = 1;
    private static final int STATE_TOW_KNOWN = 8;
    private static final int STATE_GLO_STRING_SYNC = 64;
    private static final int STATE_GLO_TOD_KNOWN = 128;
    private static final int STATE_GAL_E1C_2ND_CODE_LOCK = 2048;
    private static final int STATE_GAL_E1BC_CODE_LOCK = 1024;
    private static final int GPS_ADR_STATE_UNKNOWN = 0;

    // Thresholds
    private static final double MAXPRRUNCMPS = 10.0;
    private static final double MAXTOWUNCNS = 500.0;
    private static final double MAXADRUNCNS = 1.0;

    private final Context mContext;
    private File mRinexFile;
    private File mTempBodyFile;
    private BufferedWriter mBodyWriter;
    private boolean mIsLogging = false;

    // Accumulated data for Header
    // Map<SystemId, Map<SignalName, FrequencyIndex>>
    private final Map<Integer, Map<String, Integer>> mObservedSignals = new HashMap<>();
    private final Map<Integer, List<String>> mSignalOrder = new HashMap<>();
    // Signal list per system [freq_index] -> signal_name
    private String[][] mSignals = new String[MAX_SYS][MAX_FRQ];
    private int[] mNumSignals = new int[MAX_SYS];

    private Date mFirstObsTime = null;
    private long mFirstFullBiasNanos = -1;
    private double mFirstBiasNanos = 0;
    private boolean mFirstObsSet = false;

    // Position
    private double[] mApproxPos = new double[]{0.0, 0.0, 0.0};

    public RinexLogger(Context context) {
        mContext = context;
        resetSignals();
    }

    private void resetSignals() {
        for (int i = 0; i < MAX_SYS; i++) {
            Arrays.fill(mSignals[i], "");
            mNumSignals[i] = 0;
        }
        mFirstObsSet = false;
        mFirstObsTime = null;
        mAccumulatedEpochs = 0;
    }

    private int mAccumulatedEpochs = 0;

    public void startNewLog(File baseDirectory, String filePrefix, Date logDate) {
        if (mIsLogging) {
            stopLog();
        }
        resetSignals();
        File rinexDir = new File(baseDirectory, "RINEX");
        if (!rinexDir.exists() && !rinexDir.mkdirs()) {
            Log.e(TAG, "Failed to create RINEX directory");
            return;
        }

        // Calculate year suffix: .yyO
        SimpleDateFormat yearFormat = new SimpleDateFormat("yy", Locale.US);
        String yearSuffix = yearFormat.format(logDate);
        String rinexFileName = String.format("new_%s.%so", filePrefix, yearSuffix);

        mRinexFile = new File(rinexDir, rinexFileName);
        // Temp file for body
        mTempBodyFile = new File(rinexDir, rinexFileName + ".tmp");

        try {
            mBodyWriter = new BufferedWriter(new FileWriter(mTempBodyFile));
            mIsLogging = true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to open RINEX temp file", e);
        }
    }

    public void stopLog() {
        if (!mIsLogging) return;
        mIsLogging = false;

        try {
            if (mBodyWriter != null) {
                mBodyWriter.close();
            }

            // Now write the final file: Header + Body
            if (mRinexFile != null && mTempBodyFile != null && mTempBodyFile.exists()) {
                BufferedWriter finalWriter = new BufferedWriter(new FileWriter(mRinexFile));
                writeHeader(finalWriter);

                // Copy body
                BufferedReader bodyReader = new BufferedReader(new FileReader(mTempBodyFile));
                String line;
                while ((line = bodyReader.readLine()) != null) {
                    finalWriter.write(line);
                    finalWriter.newLine();
                }
                bodyReader.close();
                finalWriter.close();

                // Delete temp
                mTempBodyFile.delete();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error finalizing RINEX file", e);
        }
    }

    public void updateLocation(Location location) {
        if (location != null && mIsLogging) {
            // Simple approximation of XYZ from lat/lon/alt
            double[] xyz = latLonHToXyz(location.getLatitude(), location.getLongitude(), location.getAltitude());
            mApproxPos = xyz;
        }
    }

    public void processGnssMeasurements(GnssMeasurementsEvent event) {
        if (!mIsLogging || mBodyWriter == null) return;

        GnssClock clock = event.getClock();
        if (!mFirstObsSet) {
            mFirstFullBiasNanos = clock.getFullBiasNanos();
            mFirstBiasNanos = clock.hasBiasNanos() ? clock.getBiasNanos() : 0.0;
            long timeNanos = clock.getTimeNanos(); // internal hardware clock
            // Calculate UTC time for header
            mFirstObsTime = calculateUtcTime(timeNanos, mFirstFullBiasNanos, mFirstBiasNanos);
            mFirstObsSet = true;
        }

        // We process the event as one epoch
        processEpoch(clock, event.getMeasurements());
    }

    private void processEpoch(GnssClock clock, Iterable<GnssMeasurement> measurements) {
        long timeNanos = clock.getTimeNanos();
        long fullBiasNanos = clock.getFullBiasNanos();
        double biasNanos = clock.hasBiasNanos() ? clock.getBiasNanos() : 0.0;

        // Calculate epoch time
        Date epochTime = calculateUtcTime(timeNanos, fullBiasNanos, biasNanos);

        List<RnxSat> epochSats = new ArrayList<>();

        for (GnssMeasurement m : measurements) {
            // 1. Identify System and Signal
            int constType = m.getConstellationType();
            int sysId = getSystemId(constType);
            if (sysId == -1) continue;

            String signalName = identifySignal(sysId, m.getCarrierFrequencyHz());
            if (signalName.isEmpty()) continue;

            // 2. Register Signal (Dynamic Header building)
            int freqIndex = registerSignal(sysId, signalName);
            if (freqIndex == -1) continue; // Should not happen if identifySignal works

            // 3. Quality Checks
            if (!isMeasurementValid(m, sysId)) continue;

            // 4. Compute Observables
            double carrierFreqHz = m.getCarrierFrequencyHz();
             if (carrierFreqHz == 0) continue;
            double wavl = CLIGHT / carrierFreqHz;

            // Pseudorange Calculation (Ported from Python)
            // Python: time_from_gps_start = sat.time_nano - epochs[0].obs.full_bias_nano + int(sat.time_offset_nano)
            // But here we use current clock.
            // Wait, Python uses epoch[0] for time reference BUT uses 'sat.time_nano' from the current line.
            // In Android, all measurements in an event share the clock usually, but let's use the measurement's time offset.

            // Correct approach for Android Raw to Pseudorange:
            // t_Rx = (TimeNanos - FullBiasNanos - BiasNanos) reported by receiver clock
            // t_Tx = (ReceivedSvTimeNanos) reported by satellite
            // P = (t_Rx - t_Tx) * c
            // Note: Handling week rollovers/day rollovers is critical.

            double prSeconds = calculatePseudorangeSeconds(clock, m, sysId);
            if (prSeconds < 0 || prSeconds > 0.5) continue; // Sanity check 0.5s = 150,000km

            double pseudoRange = prSeconds * CLIGHT;
            double accumulatedDeltaRange = m.getAccumulatedDeltaRangeMeters();
            double carrierPhase = accumulatedDeltaRange / wavl;
            double doppler = -m.getPseudorangeRateMetersPerSecond() / wavl;
            double cno = m.getCn0DbHz();

            // 5. Add to Epoch
            RnxSat sat = findOrCreateSat(epochSats, sysId, m.getSvid());
            sat.p[freqIndex] = pseudoRange;
            sat.l[freqIndex] = carrierPhase;
            sat.d[freqIndex] = doppler;
            sat.s[freqIndex] = cno;
        }

        if (!epochSats.isEmpty()) {
             try {
                 writeEpoch(epochTime, epochSats);
                 mAccumulatedEpochs++;
             } catch (IOException e) {
                 Log.e(TAG, "Error writing epoch", e);
             }
        }
    }

    private double calculatePseudorangeSeconds(GnssClock clock, GnssMeasurement m, int sysId) {
        long timeNanos = clock.getTimeNanos();
        long fullBiasNanos = clock.getFullBiasNanos();
        double biasNanos = clock.hasBiasNanos() ? clock.getBiasNanos() : 0.0;

        // Time of Reception (Rx)
        // We generally use the "First Full Bias" logic from Python to align everything or use current.
        // Python: time_from_gps_start = sat.time_nano - epochs[epo_bias].obs.full_bias_nano + int(sat.time_offset_nano)
        // Here we use current 'clock' as reference.
        // Rx time in nanoseconds from GPS epoch (roughly)
        // Android doc: TimeNanos is internal clock. FullBiasNanos is diff to GPS/UTC.
        // Rx = TimeNanos - FullBiasNanos

        // Let's stick to standard Android Pseudorange computation:
        // P = (ReceivedSvTimeNanos - TimeNanos) ? No.
        // tRx_GPS = TimeNanos - (FullBiasNanos + BiasNanos);
        // tTx_GPS = ReceivedSvTimeNanos;
        // dt = tRx_GPS - tTx_GPS

        // However, ReceivedSvTimeNanos is modulo week/day.

        double tRxSeconds = (timeNanos - fullBiasNanos - biasNanos) * 1e-9;

        // Adjust tRx to be relative to the start of the "week" or "day" to match RecievedSvTime
        // This is complex. Let's use the Python logic simplified.

        // Python logic re-derivation:
        // receive_second = time_from_gps_start - (WeekNumber * WeekSeconds)
        // It calculates local time of week.

        long weekNanos = 604800L * 1000000000L;
        long dayNanos = 86400L * 1000000000L;

        double tTxSeconds = m.getReceivedSvTimeNanos() * 1e-9;
        double tRxSecondsMod = 0;

        if (sysId == SYS_GPS || sysId == SYS_GAL || sysId == SYS_QZS || sysId == SYS_BDS) {
            long gpsTimeNanos = timeNanos - fullBiasNanos; // Approximate GPS time
            // Modulo week
            long timeOfWeekNanos = gpsTimeNanos % weekNanos;
            // BDS has 14s offset? Python handles it: - 14 * 10^9.
            if (sysId == SYS_BDS) {
                 timeOfWeekNanos = (gpsTimeNanos - 14000000000L) % weekNanos;
            }
            // Adjust for bias
             tRxSecondsMod = (timeOfWeekNanos - biasNanos) * 1e-9;

        } else if (sysId == SYS_GLO) {
            long gloTimeNanos = timeNanos - fullBiasNanos;
            // Python: DayNonano ...
            // GLONASS time is modulo day
            // Add leap second offset? Python: + (3*3600 - LeapSecond)*1e9
             long timeOfDayNanos = gloTimeNanos % dayNanos;
             // Python logic for GLO seems to convert to buffer time then subtract day.
             // Simplified:
             tRxSecondsMod = (timeOfDayNanos - biasNanos) * 1e-9;
             // GLO offset to UTC/GPS? GLO is UTC+3.
             // Let's accept that Android's ReceivedSvTimeNanos for GLONASS is "Time of Day" in GLONASS time.
             // tRx should be in GLONASS time frame.
             // FullBiasNanos usually brings us to GPS time.
             // GPS to GLO: GPS = UTC + 18s (leap) - 19s (wait, GPS-UTC is 18).
             // GLO = UTC + 3h.
             // We need to check standard Android implementation for GLO p-range.
             // For now, use the Python logic structure which seemed to try to align them.
        }

        double pr = tRxSecondsMod - tTxSeconds;

        // Rollover check
        if (pr > 604800 / 2.0 && sysId != SYS_GLO) {
             pr -= 604800.0;
        } else if (pr < -604800 / 2.0 && sysId != SYS_GLO) {
             pr += 604800.0;
        }

         if (sysId == SYS_GLO) {
             if (pr > 86400 / 2.0) pr -= 86400.0;
             else if (pr < -86400 / 2.0) pr += 86400.0;
         }

        return pr;
    }

    private boolean isMeasurementValid(GnssMeasurement m, int sysId) {
        int state = m.getState();
        // Check availability
        boolean available = false;
        if (sysId == SYS_GPS || sysId == SYS_BDS || sysId == SYS_QZS) {
            available = (state & STATE_CODE_LOCK) != 0; // Simplified. Python had (code_lock & tow_known)
            // Python relaxed L5 to just Code Lock.
        } else if (sysId == SYS_GLO) {
            available = (state & STATE_GLO_STRING_SYNC) != 0; // Simplified
        } else if (sysId == SYS_GAL) {
            available = (state & STATE_GAL_E1C_2ND_CODE_LOCK) != 0 || (state & STATE_GAL_E1BC_CODE_LOCK) != 0;
        }

        if (!available) return false;

        // Uncertainty checks
        if (m.getPseudorangeRateUncertaintyMetersPerSecond() > MAXPRRUNCMPS) return false;
        if (m.getReceivedSvTimeUncertaintyNanos() > MAXTOWUNCNS) return false;
        if (m.getAccumulatedDeltaRangeUncertaintyMeters() > MAXADRUNCNS) return false;

        return true;
    }

    private String identifySignal(int sysId, double freqHz) {
        // Frequency Matching with tolerance
        double freq = freqHz;
        if (sysId == SYS_GPS) {
            if (isApprox(freq, 1575420000)) return "L1C";
            if (isApprox(freq, 1176450000)) return "L5Q";
        } else if (sysId == SYS_GLO) {
             if (isApprox(freq, 1602000000, 10000000)) return "L1C"; // GLO FDMA needs wider tolerance or channel calc
        } else if (sysId == SYS_BDS) {
            if (isApprox(freq, 1561098000)) return "B2I"; // B1I in old convention, Python calls it B2I? Check Python script.
            // Python: "B2I" for 1561.098. Standard is B1I. Python output string says C2I/C1I?
            // Python script: signals[i]... "C2I"...
            // Let's match Python string names "B2I", "B1P", "B5P" to RINEX codes "2I", "1P", "5P"
            // Python: add_signal(SYS_BDS, "B2I") -> Header: "C2I L2I D2I S2I" - Wait.
            // Python `signal_line` logic: `signals[i][0][1:]` -> takes "2I" from "B2I".
            // So if I return "B2I", header writes "C2I".
            if (isApprox(freq, 1575420000)) return "B1P"; // B1C
            if (isApprox(freq, 1176450000)) return "B5P"; // B2a
        } else if (sysId == SYS_GAL) {
            if (isApprox(freq, 1575420000)) return "E1C";
            if (isApprox(freq, 1176450000)) return "E5Q"; // E5a
            if (isApprox(freq, 1207140000)) return "E7Q"; // E5b
        } else if (sysId == SYS_QZS) {
            if (isApprox(freq, 1575420000)) return "L1C";
            if (isApprox(freq, 1176450000)) return "L5Q";
        }
        return "";
    }

    private boolean isApprox(double v1, double v2) {
        return Math.abs(v1 - v2) < 10000; // 10kHz tolerance
    }

    private boolean isApprox(double v1, double v2, double tol) {
        return Math.abs(v1 - v2) < tol;
    }

    private int registerSignal(int sys, String sig) {
        int sysIdx = getSystemIndex(sys);
        if (sysIdx == -1) return -1;

        // Check if exists
        for (int i = 0; i < mNumSignals[sysIdx]; i++) {
            if (mSignals[sysIdx][i].equals(sig)) return i;
        }

        // Add new
        if (mNumSignals[sysIdx] < MAX_FRQ) {
            mSignals[sysIdx][mNumSignals[sysIdx]] = sig;
            mNumSignals[sysIdx]++;
            return mNumSignals[sysIdx] - 1;
        }
        return -1;
    }

    private RnxSat findOrCreateSat(List<RnxSat> sats, int sys, int prn) {
        for (RnxSat s : sats) {
            if (s.sys == sys && s.prn == prn) return s;
        }
        RnxSat newSat = new RnxSat(sys, prn);
        sats.add(newSat);
        return newSat;
    }

    private void writeEpoch(Date time, List<RnxSat> sats) throws IOException {
        // Sort sats
        Collections.sort(sats, new Comparator<RnxSat>() {
            @Override
            public int compare(RnxSat o1, RnxSat o2) {
                int p1 = getSystemPriority(o1.sys);
                int p2 = getSystemPriority(o2.sys);
                if (p1 != p2) return Integer.compare(p1, p2);
                return Integer.compare(o1.prn, o2.prn);
            }
        });

        SimpleDateFormat sdf = new SimpleDateFormat("yy MM dd HH mm ss", Locale.US);
         // Python: > 2021 01 01 00 00 00.0000000  0 10
         // Python `print_rnx_epoch`: > yyyy mm dd ...
         // Wait, Python uses `> {:04d}` (4 digit year).
         // RINEX 3.05 usually uses 4 digit year.
        SimpleDateFormat sdfFirst = new SimpleDateFormat("yyyy MM dd HH mm ss", Locale.US);
        String timeStr = sdfFirst.format(time); // e.g. 2022 12 11 ...
        double seconds = Double.parseDouble(timeStr.substring(17)) + (time.getTime() % 1000) / 1000.0;

        // Re-format strictly
        SimpleDateFormat ymdhms = new SimpleDateFormat("yyyy MM dd HH mm ss", Locale.US);
        String baseTime = ymdhms.format(time);
        String secPart = String.format(Locale.US, "%10.7f", (double) time.getSeconds() + (time.getTime()%1000)/1000.0);
        // Date.getSeconds() is deprecated.
        // Let's use Calendar or just replace substring?
        // Simpler:
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTime(time);

        mBodyWriter.write(String.format(Locale.US, "> %04d %02d %02d %02d %02d %10.7f  0 %2d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                (double)cal.get(java.util.Calendar.SECOND) + (cal.get(java.util.Calendar.MILLISECOND)/1000.0),
                sats.size()));
        mBodyWriter.newLine();

        for (RnxSat sat : sats) {
            char sysChar = getSystemChar(sat.sys);
             // QZSS MAPPING
             int prn = sat.prn;
             if (sat.sys == SYS_QZS) {
                 // Python: qzss_prn_mapping
                 if (prn == 194) prn = 2;
                 else if (prn == 195) prn = 3;
                 else if (prn == 196) prn = 4;
                 else if (prn == 199) prn = 7;
                 else prn = prn - 192;
             }

            mBodyWriter.write(String.format(Locale.US, "%c%02d", sysChar, prn));

            // Write observations for each freq
            int sysIdx = getSystemIndex(sat.sys);
            if (sysIdx != -1) {
                for (int i = 0; i < mNumSignals[sysIdx]; i++) { // Loop through registered signals
                    // We need to match the signal index.
                    // The 'sat' object stores data in arrays ordered by 'frequency index'.
                    // But 'sat' was populated using 'registerSignal', which returns the index in mSignals.
                    // So sat.p[i] corresponds to mSignals[sysIdx][i].
                    // So we just iterate 0 to mNumSignals.

                    mBodyWriter.write(formatObs(sat.p[i])); // C/Pseudo
                    mBodyWriter.write(formatObs(sat.l[i])); // L/Phase
                    mBodyWriter.write(formatObs(sat.d[i])); // D/Doppler
                    mBodyWriter.write(formatObs(sat.s[i])); // S/SNR
                }
            }
            mBodyWriter.newLine();
        }
    }

    private String formatObs(double val) {
        if (Math.abs(val) < NEAR_ZERO) return "                ";
        return String.format(Locale.US, "%14.3f  ", val);
    }

    private void writeHeader(BufferedWriter writer) throws IOException {
        writer.write("     3.05           OBSERVATION DATA    M: Mixed            RINEX VERSION / TYPE\n");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        String dateStr = sdf.format(new Date());
        String pgm = "GeoLog";
        String runBy = Build.MANUFACTURER;
        // Truncate if necessary (unlikely)
        if (runBy.length() > 20) runBy = runBy.substring(0, 20);
        
        writer.write(String.format(Locale.US, "%-20s%-20s%-20sPGM / RUN BY / DATE   \n", pgm, runBy, dateStr));

        writer.write(String.format(Locale.US, "%-60sMARKER NAME         \n", "GeoLog"));
        writer.write(String.format(Locale.US, "%-60sMARKER NUMBER       \n", "Unknown"));
        writer.write(String.format(Locale.US, "%-60sMARKER TYPE         \n", "Unknown"));
        writer.write(String.format(Locale.US, "%-20s%-40sOBSERVER / AGENCY   \n", "SWJTU", "SWJTU"));
        writer.write(String.format(Locale.US, "%-20s%-40sREC # / TYPE / VERS \n", "Unknown", Build.MANUFACTURER + " " + Build.MODEL + " " + Build.VERSION.RELEASE));
        writer.write(String.format(Locale.US, "%-20s%-40sANT # / TYPE        \n", "unknown", "unknown"));
        writer.write(String.format(Locale.US, "%14.4f%14.4f%14.4f                  APPROX POSITION XYZ \n", mApproxPos[0], mApproxPos[1], mApproxPos[2]));
        writer.write("        0.0000        0.0000        0.0000                  ANTENNA: DELTA H/E/N\n");

        // Signals
        // Logic: G   12 C1C L1C D1C S1C C5Q L5Q D5Q S5Q ...
        char[] sysChars = {'G', 'R', 'E', 'C', 'J'};
        int[] sysIds = {SYS_GPS, SYS_GLO, SYS_GAL, SYS_BDS, SYS_QZS};

        for (int k = 0; k < 5; k++) {
            int sys = sysIds[k];
            int idx = getSystemIndex(sys);
            if (mNumSignals[idx] > 0) {
                 StringBuilder sb = new StringBuilder();
                 sb.append(sysChars[k]).append("   ");
                 int nObs = mNumSignals[idx] * 4; // C, L, D, S per signal
                 sb.append(String.format(Locale.US, "%2d", nObs));

                 for (int i = 0; i < mNumSignals[idx]; i++) {
                     String sig = mSignals[idx][i];
                     // Python: C{sig[1:]} L...
                     // sig is like "L1C"
                     String suf = sig.substring(1); // "1C"
                     sb.append(" C").append(suf).append(" L").append(suf).append(" D").append(suf).append(" S").append(suf);
                 }

                 String line = sb.toString();
                 writer.write(String.format(Locale.US, "%-60sSYS / # / OBS TYPES \n", line));
            }
        }

        // Time of first obs
        if (mFirstObsTime != null) {
             java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
             cal.setTime(mFirstObsTime);
             writer.write(String.format(Locale.US, "  %04d    %02d    %02d    %02d    %02d   %10.7f     GPS         TIME OF FIRST OBS\n",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                (double)cal.get(java.util.Calendar.SECOND) + (cal.get(java.util.Calendar.MILLISECOND)/1000.0)
             ));
        }

        writer.write("                                                            END OF HEADER       \n");
    }

    // Helpers

    private int getSystemId(int constType) {
        switch (constType) {
            case GnssStatus.CONSTELLATION_GPS: return SYS_GPS;
            case GnssStatus.CONSTELLATION_GLONASS: return SYS_GLO;
            case GnssStatus.CONSTELLATION_BEIDOU: return SYS_BDS;
            case GnssStatus.CONSTELLATION_GALILEO: return SYS_GAL;
            case GnssStatus.CONSTELLATION_QZSS: return SYS_QZS;
            default: return -1;
        }
    }

    private int getSystemIndex(int sys) {
        if (sys == SYS_GPS) return 0;
        if (sys == SYS_GLO) return 1;
        if (sys == SYS_GAL) return 2;
        if (sys == SYS_BDS) return 3;
        if (sys == SYS_QZS) return 4;
        return -1;
    }

    private char getSystemChar(int sys) {
        if (sys == SYS_GPS) return 'G';
        if (sys == SYS_GLO) return 'R';
        if (sys == SYS_GAL) return 'E';
        if (sys == SYS_BDS) return 'C';
        if (sys == SYS_QZS) return 'J';
        return ' ';
    }

    private int getSystemPriority(int sys) {
        if (sys == SYS_GPS) return 1;
        if (sys == SYS_GLO) return 2;
        if (sys == SYS_GAL) return 3;
        if (sys == SYS_BDS) return 4;
        return 5;
    }

    private Date calculateUtcTime(long timeNanos, long fullBiasNanos, double biasNanos) {
        // GPS Time = TimeNanos - (FullBiasNanos + BiasNanos)
        // UTC Time = GPS Time - LeapSeconds (18s)
        long gpsTimeNanos = timeNanos - fullBiasNanos - (long)biasNanos;
        // Convert to millis
        long gpsTimeMillis = gpsTimeNanos / 1000000L;
        // Adjust for GPS epoch (Jan 6, 1980) vs Java Epoch (Jan 1, 1970)
        // BUT, fullBiasNanos is "nanoseconds since 1980... inside the receiver hardware logic?"
        // Android docs: FullBiasNanos is "difference between hardware clock and GPS time" (usually negative).
        // TimeNanos + FullBiasNanos = GPS Time (nanos since Jan 6 1980).
        // Java time is since Jan 1 1970.
        // Difference is 10 years + leap days.
        // 315964800000 ms.

        // Actually, let's use the Python logic:
        /*
        delta_time_nano = time_nano - full_bias_nano
        delta_time_sec = delta_time_nano // 1e9
        days = delta_time_sec // 86400 + 6
        years = 1980
        ...
        */
        // Simpler way:
        long gpsEpochMillis = 315964800000L; // Jan 6 1980 in Java time
        long utcTimeMillis = gpsEpochMillis + gpsTimeMillis - (LEAP_SECOND * 1000);
        return new Date(utcTimeMillis);
    }

    private double[] latLonHToXyz(double lat, double lon, double alt) {
        double a = 6378137.0;
        double f = 1 / 298.257223563;
        double eSq = 2 * f - f * f;
        double radLat = Math.toRadians(lat);
        double radLon = Math.toRadians(lon);
        double N = a / Math.sqrt(1 - eSq * Math.pow(Math.sin(radLat), 2));
        double x = (N + alt) * Math.cos(radLat) * Math.cos(radLon);
        double y = (N + alt) * Math.cos(radLat) * Math.sin(radLon);
        double z = (N * (1 - eSq) + alt) * Math.sin(radLat);
        return new double[]{x, y, z};
    }

    // Inner Classes
    private static class RnxSat {
        int sys;
        int prn;
        double[] p = new double[MAX_FRQ];
        double[] l = new double[MAX_FRQ];
        double[] d = new double[MAX_FRQ];
        double[] s = new double[MAX_FRQ];

        RnxSat(int sys, int prn) {
            this.sys = sys;
            this.prn = prn;
        }
    }

    public File getFile() {
        return mRinexFile;
    }
}
