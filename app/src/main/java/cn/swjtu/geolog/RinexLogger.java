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

    // Measurement States
    private static final int STATE_CODE_LOCK = 1;       // 2^0
    private static final int STATE_TOW_DECODED = 8;     // 2^3
    private static final int STATE_MSEC_AMBIGUOUS = 16; // 2^4
    private static final int STATE_GLO_STRING_SYNC = 64;// 2^6
    private static final int STATE_GLO_TOD_DECODED = 128; // 2^7
    private static final int STATE_GAL_E1C_2ND_CODE_LOCK = 2048; // 2^11
    private static final int STATE_GAL_E1BC_CODE_LOCK = 1024; // 2^10

    // ADR States
    private static final int ADR_STATE_VALID = 1;
    private static final int ADR_STATE_RESET = 2;
    private static final int ADR_STATE_CYCLE_SLIP = 4;
    private static final int ADR_STATE_HALF_CYCLE_RESOLVED = 8;
    private static final int ADR_STATE_HALF_CYCLE_REPORTED = 16;

    // LLI Flags
    private static final int LLI_SLIP = 0x01;
    private static final int LLI_HALFC = 0x02;
    private static final int LLI_BOCTRK = 0x04;

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
    private final Map<Integer, Map<String, Integer>> mObservedSignals = new HashMap<>();
    private final Map<Integer, List<String>> mSignalOrder = new HashMap<>();
    // Signal list per system [freq_index] -> signal_name
    private String[][] mSignals = new String[MAX_SYS][MAX_FRQ];
    private int[] mNumSignals = new int[MAX_SYS];

    // Reference Clock State for Continuity
    private int mLastHwClockDiscontinuityCount = -1;
    private long mRefFullBiasNanos = 0;
    private double mRefBiasNanos = 0.0;

    private Date mFirstObsTime = null;
    private long mFirstFullBiasNanos = -1;
    private double mFirstBiasNanos = 0;
    private boolean mFirstObsSet = false;

    // Previous Epoch for Galileo check
    private List<RnxSat> mPreviousEpochSats = new ArrayList<>();
    private long mPreviousEpochTimeMillis = -1;

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
        mLastHwClockDiscontinuityCount = -1;
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

        // Update Reference Clock if Discontinuity Occurs
        int discontinuityCount = clock.getHardwareClockDiscontinuityCount();
        if (mLastHwClockDiscontinuityCount == -1 || discontinuityCount != mLastHwClockDiscontinuityCount) {
             mLastHwClockDiscontinuityCount = discontinuityCount;
             mRefFullBiasNanos = clock.getFullBiasNanos();
             mRefBiasNanos = clock.hasBiasNanos() ? clock.getBiasNanos() : 0.0;
        }

        if (!mFirstObsSet) {
            long timeNanos = clock.getTimeNanos(); // internal hardware clock
            // Calculate GPS time for header using the Reference Bias,
            mFirstObsTime = calculateRinexDate(timeNanos, mRefFullBiasNanos, mRefBiasNanos);
            mFirstObsSet = true;
        }

        // We process the event as one epoch
        processEpoch(clock, event.getMeasurements());
    }

    private void processEpoch(GnssClock clock, Iterable<GnssMeasurement> measurements) {
        long timeNanos = clock.getTimeNanos();

        // Use Reference Bias for Epoch Time
        Date epochTime = calculateRinexDate(timeNanos, mRefFullBiasNanos, mRefBiasNanos);
        long currentEpochMillis = epochTime.getTime();

        List<RnxSat> epochSats = new ArrayList<>();

        // Check for Galileo 4ms correction if consecutive epoch (approx 1s diff)
        boolean checkGalileo4ms = false;
        if (mPreviousEpochTimeMillis != -1) {
            long diff = Math.abs(currentEpochMillis - mPreviousEpochTimeMillis);
            if (Math.abs(diff - 1000) < 100) { // Approx 1 second
                checkGalileo4ms = true;
            }
        }

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

            // Use Reference Bias for Pseudorange Calculation
            double prSeconds = calculatePseudorangeSeconds(clock, m, sysId, mRefFullBiasNanos, mRefBiasNanos);
            if (prSeconds < 0 || prSeconds > 0.5) continue; // Sanity check 0.5s = 150,000km

            double pseudoRange = prSeconds * CLIGHT;
            double accumulatedDeltaRange = m.getAccumulatedDeltaRangeMeters();
            double carrierPhase = accumulatedDeltaRange / wavl;
            double doppler = -m.getPseudorangeRateMetersPerSecond() / wavl;
            double cno = m.getCn0DbHz();
            int adrState = m.getAccumulatedDeltaRangeState();

            // Equivalent to checking validity. If not valid, phase = 0.
            if ((adrState & ADR_STATE_VALID) == 0) {
                carrierPhase = 0.0;
            }

            // 5. Add to Epoch
            RnxSat sat = findOrCreateSat(epochSats, sysId, m.getSvid());
            sat.p[freqIndex] = pseudoRange;
            sat.l[freqIndex] = carrierPhase;
            sat.d[freqIndex] = doppler;
            sat.s[freqIndex] = cno;

            // LLI Calculation
            sat.lli[freqIndex] = 0;
            if ((adrState & ADR_STATE_HALF_CYCLE_REPORTED) != 0 && (adrState & ADR_STATE_HALF_CYCLE_RESOLVED) == 0) {
                 sat.lli[freqIndex] |= LLI_HALFC;
            }
            if ((adrState & ADR_STATE_CYCLE_SLIP) != 0) {
                 sat.lli[freqIndex] |= LLI_SLIP;
            }
        }

        // Apply Galileo 4ms correction
        if (checkGalileo4ms && !mPreviousEpochSats.isEmpty()) {
            double range4ms = 0.004 * CLIGHT; // ~1199km
            double threshold = 1500.0;

            for (RnxSat sat : epochSats) {
                if (sat.sys == SYS_GAL) {
                    // Find corresponding sat in prev epoch
                    RnxSat prevSat = null;
                    for (RnxSat p : mPreviousEpochSats) {
                        if (p.sys == SYS_GAL && p.prn == sat.prn) {
                            prevSat = p;
                            break;
                        }
                    }
                    if (prevSat == null) continue;

                    for (int i = 0; i < MAX_FRQ; i++) {
                         double pCurr = sat.p[i];
                         double pPrev = prevSat.p[i];

                         if (pCurr != 0 && pPrev != 0) {
                             if (Math.abs(pCurr - pPrev - range4ms) < threshold || Math.abs(pCurr - pPrev + range4ms) < threshold) {
                                  int sign = (pCurr - pPrev) < 0 ? -1 : 1;
                                  sat.p[i] = sat.p[i] - sign * range4ms;
                             }
                         }
                    }
                }
            }
        }

        if (!epochSats.isEmpty()) {
             try {
                 writeEpoch(epochTime, epochSats);
                 mAccumulatedEpochs++;
                 mPreviousEpochSats = epochSats; // Store for next comparison
                 mPreviousEpochTimeMillis = currentEpochMillis;
             } catch (IOException e) {
                 Log.e(TAG, "Error writing epoch", e);
             }
        }
    }

    private double calculatePseudorangeSeconds(GnssClock clock, GnssMeasurement m, int sysId, long refFullBiasNanos, double refBiasNanos) {
        long timeNanos = clock.getTimeNanos();
        // Uses Reference Bias values passed in args
        double timeOffsetNanos = m.getTimeOffsetNanos();

        // Calculate tRxSeconds in the time system of the constellation (modulo week/day)

        long weekNanos = 604800L * 1000000000L;
        long dayNanos = 86400L * 1000000000L;

        // tTx is the ReceivedSvTime from the satellite (already modulo week/day usually)
        double tTxSeconds = m.getReceivedSvTimeNanos() * 1e-9;

        double tRxSecondsMod = 0;

        // Calculate Time of Reception (tRx) relative to GPS start, then adjust to Constellation Time
        // using REFERENCE biases
        long gpsTimeNanos = timeNanos - refFullBiasNanos + (long)timeOffsetNanos; // Raw GPS time (approx)

        if (sysId == SYS_GPS || sysId == SYS_GAL || sysId == SYS_QZS || sysId == SYS_BDS) {
            // Modulo week
            long timeOfWeekNanos = gpsTimeNanos % weekNanos;

            // BDS has 14s offset relative to GPS
            if (sysId == SYS_BDS) {
                 // Note: Java % can return negative if operand is negative, but gpsTimeNanos is huge positive (-fullBias is +)
                 // BDS time = GPS time - 14s
                 timeOfWeekNanos = (gpsTimeNanos - 14000000000L) % weekNanos;
            }

            // Adjust for bias (reference bias)
            tRxSecondsMod = (timeOfWeekNanos - refBiasNanos) * 1e-9;

        } else if (sysId == SYS_GLO) {
            // GLONASS: main.cpp calculates receive_second based on DayNonano
            // receive_second = time_from_gps_start - DayNonano + (3*3600 - LeapSecond)*1e9

            // DayNonano aligns gpsTimeNanos to the start of the "day"
            long timeOfDayNanos = gpsTimeNanos % dayNanos;

            // Apply GLONASS offset: UTC+3h vs GPS(UTC+Leap) => GLO = GPS - Leap + 3h
            long gloOffsetNanos = (3 * 3600 - LEAP_SECOND) * 1000000000L;

            tRxSecondsMod = (timeOfDayNanos + gloOffsetNanos - refBiasNanos) * 1e-9;
        }

        double pr = tRxSecondsMod - tTxSeconds;

        // Rollover check
        // Check for week rollover in receive_second
        if (pr > 604800 / 2.0 && sysId != SYS_GLO) {
             double delS = Math.round(pr / 604800.0) * 604800.0;
             pr -= delS;
        } else if (pr < -604800 / 2.0 && sysId != SYS_GLO) { // Handle negative just in case
             double delS = Math.round(pr / 604800.0) * 604800.0;
             pr -= delS;
        }

        // additional modulo checks
        if ((sysId == SYS_GPS || sysId == SYS_GAL || sysId == SYS_BDS || sysId == SYS_QZS) && pr > 604800) {
             pr %= 604800.0;
        }
        if (sysId == SYS_GLO) {
             if (pr > 86400 / 2.0) pr -= 86400.0; // Standard rollover check for GLO (which is day based sometimes)
             else if (pr < -86400 / 2.0) pr += 86400.0;

             if (pr > 86400) pr %= 86400.0;
        }

        return pr;
    }

    private boolean isMeasurementValid(GnssMeasurement m, int sysId) {
        int state = m.getState();

        // 0. Millisecond Ambiguity Check: Must be 0 for valid pseudorange
        if ((state & STATE_MSEC_AMBIGUOUS) != 0) return false;

        // 1. All systems must satisfy STATE_TOW_DECODED (or equivalent for GLO)
        boolean towDecoded = false;
        if (sysId == SYS_GLO) {
            towDecoded = (state & STATE_GLO_TOD_DECODED) != 0;
        } else {
            towDecoded = (state & STATE_TOW_DECODED) != 0;
        }
        if (!towDecoded) return false;

        boolean codeLock = false;
        if (sysId == SYS_GAL) {
            codeLock = (state & STATE_GAL_E1BC_CODE_LOCK) != 0 || (state & STATE_GAL_E1C_2ND_CODE_LOCK) != 0;
            if (!codeLock) return false;
        }
        else if (sysId == SYS_GPS || sysId == SYS_BDS || sysId == SYS_QZS) {
            if ((state & STATE_CODE_LOCK) == 0) return false;
        } else if (sysId == SYS_GLO) {
            if ((state & STATE_GLO_STRING_SYNC) == 0) return false;
        }

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
            if (isApprox(freq, 1561098000)) return "B2I"; // B1I (RINEX code 2I)
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

        // Use Calendar for precise time handling
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

                    mBodyWriter.write(formatObs(sat.p[i])); // C/Pseudo
                    // Carrier Phase includes LLI
                    int lli = sat.lli[i] & (LLI_SLIP | LLI_HALFC | LLI_BOCTRK);
                    mBodyWriter.write(formatPhase(sat.l[i], lli)); // L/Phase

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

    private String formatPhase(double val, int lli) {
        if (Math.abs(val) < NEAR_ZERO) return "              ";
        if (Math.abs(val) < NEAR_ZERO) {
            return "              "; // 14 spaces to align
        }
        return String.format(Locale.US, "%13.3f%1d", val, lli);
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

    private Date calculateRinexDate(long timeNanos, long fullBiasNanos, double biasNanos) {
        // GPS Time = TimeNanos - (FullBiasNanos + BiasNanos)
        // RINEX logs typically use GPS Time. By not subtracting leap seconds,
        // the Date object (printed as UTC) will visually represent GPS time.
        long gpsTimeNanos = timeNanos - fullBiasNanos - (long)biasNanos;
        // Convert to millis
        long gpsTimeMillis = gpsTimeNanos / 1000000L;
        long gpsEpochMillis = 315964800000L; // Jan 6 1980 in Java time
        long rinexTimeMillis = gpsEpochMillis + gpsTimeMillis;
        return new Date(rinexTimeMillis);
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
        int[] lli = new int[MAX_FRQ];

        RnxSat(int sys, int prn) {
            this.sys = sys;
            this.prn = prn;
        }
    }

    public File getFile() {
        return mRinexFile;
    }
}
