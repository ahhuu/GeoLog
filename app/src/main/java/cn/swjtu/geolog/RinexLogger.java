package cn.swjtu.geolog;

import android.content.Context;
import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.os.Build;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

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

    // Thresholds (Relaxed for Android devices)
    private static final double MAXPRRUNCMPS = 100.0;
    private static final double MAXTOWUNCNS = 500.0;
    private static final double MAXADRUNCNS = 10.0;

    private final Context mContext;
    private File mRinexFile;
    private File mTempBodyFile;
    private BufferedWriter mBodyWriter;
    private boolean mIsLogging = false;

    // Accumulated data for Header
    // Signal list per system [freq_index] -> signal_name (e.g., "1C", "5P")
    private String[][] mSignals = new String[MAX_SYS][MAX_FRQ];
    private int[] mNumSignals = new int[MAX_SYS];

    // GLONASS Frequency Numbers (k)
    private final Map<Integer, Integer> mGlonassFreqMap = new HashMap<>();

    // Reference Clock State for Continuity
    private int mLastHwClockDiscontinuityCount = -1;
    private long mRefFullBiasNanos = 0;
    private double mRefBiasNanos = 0.0;

    private Date mFirstObsTime = null;
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
        mGlonassFreqMap.clear();
        mFirstObsSet = false;
        mFirstObsTime = null;
        mLastHwClockDiscontinuityCount = -1;
    }

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

        SimpleDateFormat yearFormat = new SimpleDateFormat("yy", Locale.US);
        String yearSuffix = yearFormat.format(logDate);
        String rinexFileName = String.format("geo_%s.%so", filePrefix, yearSuffix);

        mRinexFile = new File(rinexDir, rinexFileName);
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

            // Write Header + Body
            if (mRinexFile != null && mTempBodyFile != null && mTempBodyFile.exists()) {
                BufferedWriter finalWriter = new BufferedWriter(new FileWriter(mRinexFile));
                writeHeader(finalWriter);

                BufferedReader bodyReader = new BufferedReader(new FileReader(mTempBodyFile));
                String line;
                while ((line = bodyReader.readLine()) != null) {
                    finalWriter.write(line);
                    finalWriter.newLine();
                }
                bodyReader.close();
                finalWriter.close();

                mTempBodyFile.delete();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error finalizing RINEX file", e);
        }
    }

    public void updateLocation(Location location) {
        if (location != null && mIsLogging) {
            double[] xyz = latLonHToXyz(location.getLatitude(), location.getLongitude(), location.getAltitude());
            mApproxPos = xyz;
        }
    }

    public void processGnssMeasurements(GnssMeasurementsEvent event) {
        if (!mIsLogging || mBodyWriter == null) return;

        GnssClock clock = event.getClock();

        int discontinuityCount = clock.getHardwareClockDiscontinuityCount();
        if (mLastHwClockDiscontinuityCount == -1 || discontinuityCount != mLastHwClockDiscontinuityCount) {
            mLastHwClockDiscontinuityCount = discontinuityCount;
            mRefFullBiasNanos = clock.getFullBiasNanos();
            mRefBiasNanos = clock.hasBiasNanos() ? clock.getBiasNanos() : 0.0;
        }

        if (!mFirstObsSet) {
            long timeNanos = clock.getTimeNanos();
            mFirstObsTime = calculateRinexDate(timeNanos, mRefFullBiasNanos, mRefBiasNanos);
            mFirstObsSet = true;
        }

        processEpoch(clock, event.getMeasurements());
    }

    private void processEpoch(GnssClock clock, Iterable<GnssMeasurement> measurements) {
        long timeNanos = clock.getTimeNanos();
        Date epochTime = calculateRinexDate(timeNanos, mRefFullBiasNanos, mRefBiasNanos);
        long currentEpochMillis = epochTime.getTime();

        List<RnxSat> epochSats = new ArrayList<>();

        boolean checkGalileo4ms = false;
        if (mPreviousEpochTimeMillis != -1) {
            long diff = Math.abs(currentEpochMillis - mPreviousEpochTimeMillis);
            if (Math.abs(diff - 1000) < 100) {
                checkGalileo4ms = true;
            }
        }

        for (GnssMeasurement m : measurements) {
            int constType = m.getConstellationType();
            int sysId = getSystemId(constType);
            if (sysId == -1) continue;

            // --- MODIFIED: Smart Signal Identification using CodeType ---
            String rawCodeType = "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (m.hasCodeType()) {
                    rawCodeType = m.getCodeType();
                }
            }
            // Use the new smart logic
            String signalName = getSmartSignalCode(sysId, m.getCarrierFrequencyHz(), rawCodeType);

            if (signalName == null || signalName.isEmpty()) continue;
            // ------------------------------------------------------------

            // GLONASS Frequency Slot
            if (sysId == SYS_GLO) {
                int svid = m.getSvid();
                Integer k = calculateGlonassSlot(m.getCarrierFrequencyHz());
                if (k != null) {
                    mGlonassFreqMap.put(svid, k);
                }
            }

            int freqIndex = registerSignal(sysId, signalName);
            if (freqIndex == -1) continue;

            if (!isMeasurementValid(m, sysId, signalName)) continue;

            double rawCarrierFreqHz = m.getCarrierFrequencyHz();
            if (rawCarrierFreqHz == 0) continue;

            // Use Nominal Frequency for Wavelength Calculation
            double nominalFreq = getNominalFrequency(sysId, rawCarrierFreqHz, m.getSvid());
            double wavl = CLIGHT / nominalFreq;

            double prSeconds = calculatePseudorangeSeconds(clock, m, sysId, mRefFullBiasNanos, mRefBiasNanos);
            if (prSeconds < 0 || prSeconds > 0.5) continue;

            double pseudoRange = prSeconds * CLIGHT;
            double accumulatedDeltaRange = m.getAccumulatedDeltaRangeMeters();
            double carrierPhase = accumulatedDeltaRange / wavl;
            double doppler = -m.getPseudorangeRateMetersPerSecond() / wavl;
            double cno = m.getCn0DbHz();
            int adrState = m.getAccumulatedDeltaRangeState();

            if ((adrState & ADR_STATE_VALID) == 0) {
                carrierPhase = 0.0;
            }

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
            if ((adrState & ADR_STATE_RESET) != 0 || (adrState & ADR_STATE_CYCLE_SLIP) != 0) {
                sat.lli[freqIndex] |= LLI_SLIP;
            }
        }

        // Apply Galileo 4ms correction
        if (checkGalileo4ms && !mPreviousEpochSats.isEmpty()) {
            double range4ms = 0.004 * CLIGHT;
            double threshold = 1500.0;

            for (RnxSat sat : epochSats) {
                if (sat.sys == SYS_GAL) {
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
                mPreviousEpochSats = epochSats;
                mPreviousEpochTimeMillis = currentEpochMillis;
            } catch (IOException e) {
                Log.e(TAG, "Error writing epoch", e);
            }
        }
    }

    // --- NEW: Smart Signal Logic ---
    private String getSmartSignalCode(int sys, double carrierFreqHz, String androidCodeType) {
        // 1. Pre-process
        double freqMhz = Math.round(carrierFreqHz / 1e5) / 10.0;
        String rawCode = (androidCodeType == null) ? "" : androidCodeType;

        // 2. Define Band ID and Default Attribute
        String bandId = "";
        String defaultAttr = "";

        // --- BDS B1I (1561.098 MHz) -> Band 2 ---
        if (sys == SYS_BDS && Math.abs(freqMhz - 1561.1) < 1.0) {
            bandId = "2";
            defaultAttr = "I";
        }
        // --- Band 1: L1 / E1 / B1C / G1 ---
        else if (Math.abs(freqMhz - 1575.4) < 1.0 || (sys == SYS_GLO && freqMhz > 1590 && freqMhz < 1615)) {
            bandId = "1";
            if (sys == SYS_BDS) defaultAttr = "P"; // B1C default Pilot
            else defaultAttr = "C";                // GPS/GLO/GAL default C/A or Pilot
        }
        // --- Band 5: L5 / E5a / B2a / QZS-L5 ---
        else if (Math.abs(freqMhz - 1176.4) < 1.0) {
            bandId = "5";
            if (sys == SYS_BDS) defaultAttr = "P"; // B2a default Pilot
            else defaultAttr = "Q";                // GPS/GAL default Quadrature
        }
        // --- Band 2: L2 / G2 ---
        else if (Math.abs(freqMhz - 1227.6) < 1.0 || (sys == SYS_GLO && freqMhz > 1230 && freqMhz < 1260)) {
            bandId = "2";
            defaultAttr = "C";
        }
        // --- Band 7: E5b / B2b ---
        else if (Math.abs(freqMhz - 1207.1) < 1.0) {
            bandId = "7";
            if (sys == SYS_BDS) defaultAttr = "I";
            else defaultAttr = "Q"; // GAL E5b default Q
        }
        // --- Band 6: B3I ---
        else if (Math.abs(freqMhz - 1268.5) < 1.0) {
            bandId = "6";
            defaultAttr = "I";
        }

        // 3. Assembly
        if (bandId.isEmpty()) return null;

        String finalAttr = rawCode.isEmpty() ? defaultAttr : rawCode;

        // 4. Correction / Translation (Fix Inconsistencies)
        // Fix BDS B2a: Android "Q" -> RINEX "P"
        if (sys == SYS_BDS && "5".equals(bandId) && "Q".equals(finalAttr)) {
            finalAttr = "P";
        }

        // 5. Ignore 1L measurements
        if ("1".equals(bandId) && "L".equals(finalAttr)) {
            return null; // Return null to skip this measurement
        }

        return bandId + finalAttr;
    }

    // Calculate GLONASS Slot Number (k)
    private Integer calculateGlonassSlot(double freq) {
        if (freq > 1.59e9) {
            double k = (freq - 1602.0e6) / 0.5625e6;
            return (int) Math.round(k);
        }
        if (freq > 1.23e9 && freq < 1.26e9) {
            double k = (freq - 1246.0e6) / 0.4375e6;
            return (int) Math.round(k);
        }
        return null;
    }

    private double getNominalFrequency(int sysId, double rawFreq, int svid) {
        if (sysId == SYS_GLO) {
            Integer k = mGlonassFreqMap.get(svid);
            if (k != null) {
                // Reconstruct exact FDMA frequency
                if (rawFreq > 1.5e9) return 1602.0e6 + k * 0.5625e6;
                else return 1246.0e6 + k * 0.4375e6;
            }
            return rawFreq;
        } else if (sysId == SYS_BDS) {
            if (Math.abs(rawFreq - 1561.098e6) < 1.0e6) {
                return 1561.098e6;
            }
        }
        // For CDMA systems (GPS, GAL, QZSS, BDS, others), round to nearest 1kHz
        return Math.round(rawFreq / 1000.0) * 1000.0;
    }

    private double calculatePseudorangeSeconds(GnssClock clock, GnssMeasurement m, int sysId, long refFullBiasNanos, double refBiasNanos) {
        long timeNanos = clock.getTimeNanos();
        double timeOffsetNanos = m.getTimeOffsetNanos();
        long weekNanos = 604800L * 1000000000L;
        long dayNanos = 86400L * 1000000000L;
        double tTxSeconds = m.getReceivedSvTimeNanos() * 1e-9;
        double tRxSecondsMod = 0;

        long gpsTimeNanos = timeNanos - refFullBiasNanos + (long)timeOffsetNanos;

        if (sysId == SYS_GPS || sysId == SYS_GAL || sysId == SYS_QZS || sysId == SYS_BDS) {
            long timeOfWeekNanos = gpsTimeNanos % weekNanos;
            if (sysId == SYS_BDS) {
                timeOfWeekNanos = (gpsTimeNanos - 14000000000L) % weekNanos;
            }
            tRxSecondsMod = (timeOfWeekNanos - refBiasNanos) * 1e-9;
        } else if (sysId == SYS_GLO) {
            long timeOfDayNanos = gpsTimeNanos % dayNanos;
            long gloOffsetNanos = (3 * 3600 - LEAP_SECOND) * 1000000000L;
            tRxSecondsMod = (timeOfDayNanos + gloOffsetNanos - refBiasNanos) * 1e-9;
        }

        double pr = tRxSecondsMod - tTxSeconds;

        if (sysId != SYS_GLO) {
            if (pr > 302400.0) pr -= 604800.0;
            else if (pr < -302400.0) pr += 604800.0;
        }

        if ((sysId == SYS_GPS || sysId == SYS_GAL || sysId == SYS_BDS || sysId == SYS_QZS) && pr > 604800) {
            pr %= 604800.0;
        }
        if (sysId == SYS_GLO) {
            if (pr > 43200.0) pr -= 86400.0;
            else if (pr < -43200.0) pr += 86400.0;
            if (pr > 86400) pr %= 86400.0;
        }

        return pr;
    }

    private boolean isMeasurementValid(GnssMeasurement m, int sysId, String signalName) {
        int state = m.getState();

        // 1. MSEC AMBIGUOUS
        if ((state & STATE_MSEC_AMBIGUOUS) != 0) return false;

        // 2. Time Decoded (TOW / TOD)
        boolean towDecoded = false;
        if (sysId == SYS_GLO) {
            towDecoded = (state & STATE_GLO_TOD_DECODED) != 0;
        } else {
            towDecoded = (state & STATE_TOW_DECODED) != 0;
        }
        if (!towDecoded) return false;

        // 3. Code Lock
        boolean codeLock = false;
        if (sysId == SYS_GAL && "1C".equals(signalName)) {
            codeLock = (state & STATE_GAL_E1BC_CODE_LOCK) != 0 ||
                    (state & STATE_GAL_E1C_2ND_CODE_LOCK) != 0;
        } else {
            codeLock = (state & STATE_CODE_LOCK) != 0;
        }

        if (!codeLock) return false;

        // Uncertainty checks
        if (m.getPseudorangeRateUncertaintyMetersPerSecond() > MAXPRRUNCMPS) return false;
        if (m.getReceivedSvTimeUncertaintyNanos() > MAXTOWUNCNS) return false;
        if (m.getAccumulatedDeltaRangeUncertaintyMeters() > MAXADRUNCNS) return false;

        return true;
    }

    private int registerSignal(int sys, String sig) {
        int sysIdx = getSystemIndex(sys);
        if (sysIdx == -1) return -1;
        for (int i = 0; i < mNumSignals[sysIdx]; i++) {
            if (mSignals[sysIdx][i].equals(sig)) return i;
        }
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
        // 过滤所有观测值全为0的卫星
        List<RnxSat> validSats = new ArrayList<>();
        for (RnxSat sat : sats) {
            boolean allZero = true;
            for (int i = 0; i < MAX_FRQ; i++) {
                if (Math.abs(sat.p[i]) > NEAR_ZERO || Math.abs(sat.l[i]) > NEAR_ZERO ||
                        Math.abs(sat.d[i]) > NEAR_ZERO || Math.abs(sat.s[i]) > NEAR_ZERO) {
                    allZero = false;
                    break;
                }
            }
            if (!allZero) validSats.add(sat);
        }

        // 排序
        Collections.sort(validSats, new Comparator<RnxSat>() {
            @Override
            public int compare(RnxSat o1, RnxSat o2) {
                int p1 = getSystemPriority(o1.sys);
                int p2 = getSystemPriority(o2.sys);
                if (p1 != p2) return Integer.compare(p1, p2);
                return Integer.compare(o1.prn, o2.prn);
            }
        });

        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTime(time);

        mBodyWriter.write(String.format(Locale.US, "> %04d %02d %02d %02d %02d %10.7f  0 %2d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                (double)cal.get(java.util.Calendar.SECOND) + (cal.get(java.util.Calendar.MILLISECOND)/1000.0),
                validSats.size()));
        mBodyWriter.newLine();

        for (RnxSat sat : validSats) {
            char sysChar = getSystemChar(sat.sys);
            int prn = sat.prn;
            if (sat.sys == SYS_QZS) {
                prn -= 192;
            }

            mBodyWriter.write(String.format(Locale.US, "%c%02d", sysChar, prn));

            int sysIdx = getSystemIndex(sat.sys);
            if (sysIdx != -1) {
                for (int i = 0; i < mNumSignals[sysIdx]; i++) {
                    mBodyWriter.write(formatObs(sat.p[i]));

                    int lli = sat.lli[i] & (LLI_SLIP | LLI_HALFC | LLI_BOCTRK);
                    mBodyWriter.write(formatPhase(sat.l[i], lli));

                    mBodyWriter.write(formatObs(sat.d[i]));
                    mBodyWriter.write(formatObs(sat.s[i]));
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
        if (Math.abs(val) < NEAR_ZERO) return "                ";
        String lliStr = (lli == 0) ? " " : String.valueOf(lli);
        return String.format(Locale.US, "%14.3f%s ", val, lliStr);
    }

    private void writeHeader(BufferedWriter writer) throws IOException {
        writer.write("     3.05           OBSERVATION DATA    M: Mixed            RINEX VERSION / TYPE\n");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        String dateStr = sdf.format(new Date());
        String pgm = "GeoLog";
        String runBy = Build.MANUFACTURER;
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

        // --- MODIFIED: Simplified Header Generation ---
        // Since getSmartSignalCode() returns standard codes (e.g. "1C", "5P"),
        char[] sysChars = {'G', 'R', 'E', 'C', 'J'};
        int[] sysIds = {SYS_GPS, SYS_GLO, SYS_GAL, SYS_BDS, SYS_QZS};

        for (int k = 0; k < 5; k++) {
            int sys = sysIds[k];
            int idx = getSystemIndex(sys);
            if (mNumSignals[idx] > 0) {
                List<String> codes = new ArrayList<>();
                for (int i = 0; i < mNumSignals[idx]; i++) {
                    String suf = mSignals[idx][i]; // e.g. "1C", "5P"
                    codes.add("C"+suf); codes.add("L"+suf); codes.add("D"+suf); codes.add("S"+suf);
                }

                int nObs = codes.size();
                List<String> firstBatch = codes.subList(0, Math.min(codes.size(), 13));
                StringBuilder sb = new StringBuilder();
                for(String c : firstBatch) sb.append(String.format("%-4s", c));

                writer.write(String.format(Locale.US, "%c  %3d %-52s SYS / # / OBS TYPES \n", sysChars[k], nObs, sb.toString()));

                for (int i = 13; i < codes.size(); i += 13) {
                    List<String> batch = codes.subList(i, Math.min(codes.size(), i + 13));
                    sb = new StringBuilder();
                    for(String c : batch) sb.append(String.format("%-4s", c));
                    writer.write(String.format(Locale.US, "      %-52s SYS / # / OBS TYPES \n", sb.toString()));
                }
            }
        }
        // -----------------------------------------------------

        if (!mGlonassFreqMap.isEmpty()) {
            Map<Integer, Integer> sortedSlots = new TreeMap<>(mGlonassFreqMap);
            int count = 0;
            int numGlo = sortedSlots.size();

            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "%3d ", numGlo));

            for (Map.Entry<Integer, Integer> entry : sortedSlots.entrySet()) {
                sb.append(String.format(Locale.US, "R%02d %2d ", entry.getKey(), entry.getValue()));
                count++;

                if (count == 8) {
                    writer.write(String.format(Locale.US, "%-60sGLONASS SLOT / FRQ #\n", sb.toString()));
                    sb = new StringBuilder("    ");
                    count = 0;
                }
            }
            if (count > 0) {
                writer.write(String.format(Locale.US, "%-60sGLONASS SLOT / FRQ #\n", sb.toString()));
            }
        }

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
        long gpsTimeNanos = timeNanos - fullBiasNanos - (long)biasNanos;
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

