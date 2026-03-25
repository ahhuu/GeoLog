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
    private static final int LEAP_SECOND = 18; // As of 2021/2026

    // System Constants
    private static final int SYS_GPS = 1;
    private static final int SYS_GLO = 3;
    private static final int SYS_QZS = 4;
    private static final int SYS_BDS = 5;
    private static final int SYS_GAL = 6;
    private static final int MAX_SYS = 10;
    private static final int MAX_FRQ = 5;

    // Measurement States
    private static final int STATE_CODE_LOCK = 1; // 2^0
    private static final int STATE_TOW_DECODED = 8; // 2^3
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
    private String[][] mSignals = new String[MAX_SYS][MAX_FRQ];
    private int[] mNumSignals = new int[MAX_SYS];
    private final Map<Integer, Integer> mGlonassFreqMap = new HashMap<>();

    // Reference Clock State for Continuity
    private int mLastHwClockDiscontinuityCount = -1;
    private long mRefFullBiasNanos = 0;
    private double mRefBiasNanos = 0.0;

    // First Observation Time (High Precision)
    private RinexTime mFirstObsTime = null;
    private boolean mFirstObsSet = false;

    // Track last observation time for duration
    private RinexTime mLastObsTime = null;

    // Previous Epoch for Galileo check
    private List<RnxSat> mPreviousEpochSats = new ArrayList<>();
    private long mPreviousEpochTimeMillis = -1;

    // Position
    private double[] mApproxPos = new double[] { 0.0, 0.0, 0.0 };

    // Naming components for output file
    private String mStationName = "GNSS00GEO";
    private String mSource = "R";   // receiver
    private String mFru = "01S";    // sampling interval
    private String mType = "MO";    // data type
    private String mStartTimeStr = ""; // YYYYDDDHHMM

    // Configurable header fields
    private String mMarkerName = "GeoLog";
    private String mMarkerNumber = "Unknown";
    private String mMarkerType = "GEODETIC";
    private String mObserver = "SWJTU";
    private String mAgency = "SWJTU";
    private String mReceiverNumber = "Unknown";
    private String mReceiverType = Build.MANUFACTURER + " " + Build.MODEL;
    private String mReceiverVersion = Build.VERSION.RELEASE;
    private String mAntennaNumber = "unknown";
    private String mAntennaType = "unknown";
    private double mAntennaDeltaH = 0.0;
    private double mAntennaDeltaE = 0.0;
    private double mAntennaDeltaN = 0.0;

    public static class HeaderSettings {
        public String stationName;
        public String markerName;
        public String markerNumber;
        public String markerType;
        public String observer;
        public String agency;
        public String receiverNumber;
        public String receiverType;
        public String receiverVersion;
        public String antennaNumber;
        public String antennaType;
        public double antennaDeltaH;
        public double antennaDeltaE;
        public double antennaDeltaN;
    }

    // --- NEW: Helper Class for High Precision Time ---
    private static class RinexTime {
        int year, month, day, hour, min;
        double sec; // Keep high precision seconds

        RinexTime(int year, int month, int day, int hour, int min, double sec) {
            this.year = year;
            this.month = month;
            this.day = day;
            this.hour = hour;
            this.min = min;
            this.sec = sec;
        }

        long toRoughMillis() {
            // Only for approximate comparisons (like Galileo 1s check)
            java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            cal.set(year, month - 1, day, hour, min, (int) sec);
            return cal.getTimeInMillis();
        }
    }

    public RinexLogger(Context context) {
        mContext = context;
        resetSignals();
    }

    public void applyHeaderSettings(HeaderSettings settings) {
        if (settings == null) {
            return;
        }
        mStationName = normalizeStationName(settings.stationName);
        mMarkerName = trimOrDefault(settings.markerName, "GeoLog");
        mMarkerNumber = trimOrDefault(settings.markerNumber, "Unknown");
        mMarkerType = trimOrDefault(settings.markerType, "GEODETIC");
        mObserver = trimOrDefault(settings.observer, "SWJTU");
        mAgency = trimOrDefault(settings.agency, "SWJTU");
        mReceiverNumber = trimOrDefault(settings.receiverNumber, "Unknown");
        mReceiverType = trimOrDefault(settings.receiverType, Build.MANUFACTURER + " " + Build.MODEL);
        mReceiverVersion = trimOrDefault(settings.receiverVersion, Build.VERSION.RELEASE);
        mAntennaNumber = trimOrDefault(settings.antennaNumber, "unknown");
        mAntennaType = trimOrDefault(settings.antennaType, "unknown");
        mAntennaDeltaH = settings.antennaDeltaH;
        mAntennaDeltaE = settings.antennaDeltaE;
        mAntennaDeltaN = settings.antennaDeltaN;
    }

    private String normalizeStationName(String stationName) {
        String fallback = "GNSS00GEO";
        if (stationName == null) {
            return fallback;
        }
        String cleaned = stationName.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        if (cleaned.length() < 4) {
            return fallback;
        }
        if (cleaned.length() > 9) {
            return cleaned.substring(0, 9);
        }
        if (cleaned.length() < 9) {
            return String.format(Locale.US, "%-9s", cleaned).replace(' ', '0');
        }
        return cleaned;
    }

    private String trimOrDefault(String value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? defaultValue : trimmed;
    }

    private String fitField(String value, int width, String defaultValue) {
        String v = trimOrDefault(value, defaultValue);
        if (v.length() > width) {
            return v.substring(0, width);
        }
        return v;
    }

    private void resetSignals() {
        for (int i = 0; i < MAX_SYS; i++) {
            Arrays.fill(mSignals[i], "");
            mNumSignals[i] = 0;
        }
        mGlonassFreqMap.clear();
        mFirstObsSet = false;
        mFirstObsTime = null;
        mLastObsTime = null;
        mStartTimeStr = "";
        mLastHwClockDiscontinuityCount = -1;
    }

    /**
     * Start a new RINEX log.  The file name is constructed using the pattern
     * SSSSMMRRR_S_YYYYDDDHHMM_DDU_FRU_DT.fff described in the project
     * requirements (station name, source, start time, duration, sample
     * interval, data type, extension).
     *
     * @param baseDirectory base folder where "RINEX" subdirectory will be
     *                      created if necessary.
     * @param stationName   optional station identifier ("SSSSMMRRR").  If
     *                      null/empty or not a valid 9‑character name, the
     *                      default "GNSS00GEO" will be used.
     *                      Previously this parameter was used as a prefix
     *                      derived from the raw TXT file name; it is still
     *                      passed from callers but is generally ignored.
     * @param logDate       date/time derived from the incoming raw TXT file
     *                      name (start of data).  Used to compute the
     *                      YYYYDDDHHMM portion.
     */
    public void startNewLog(File baseDirectory, String stationName, Date logDate) {
        if (mIsLogging) {
            stopLog();
        }
        resetSignals();
        File rinexDir = new File(baseDirectory, "RINEX");
        if (!rinexDir.exists() && !rinexDir.mkdirs()) {
            Log.e(TAG, "Failed to create RINEX directory");
            return;
        }

        // Station name comes from settings and controls the output file name.
        mStationName = normalizeStationName(mStationName);
        mSource = "R"; // always receiver
        mFru = "01S";
        mType = "MO";

        // start time string YYYYDDDHHMM
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTime(logDate);
        int year = cal.get(java.util.Calendar.YEAR);
        int doy = cal.get(java.util.Calendar.DAY_OF_YEAR);
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int minute = cal.get(java.util.Calendar.MINUTE);
        mStartTimeStr = String.format(Locale.US, "%04d%03d%02d%02d", year, doy, hour, minute);

        // Build temporary/placeholder filename (duration unknown yet) and temp body file
        String placeholderName = String.format(Locale.US, "%s_%s_%s_%s_%s_%s.%s",
                mStationName, mSource, mStartTimeStr, "XX", mFru, mType, "rnx");
        mRinexFile = new File(rinexDir, placeholderName);
        mTempBodyFile = new File(rinexDir, placeholderName + ".tmp");

        try {
            mBodyWriter = new BufferedWriter(new FileWriter(mTempBodyFile));
            mIsLogging = true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to open RINEX temp file", e);
        }
    }

    public void stopLog() {
        if (!mIsLogging)
            return;
        mIsLogging = false;

        try {
            if (mBodyWriter != null) {
                mBodyWriter.close();
            }

            if (mRinexFile != null && mTempBodyFile != null && mTempBodyFile.exists()) {
                // Determine duration string based on first/last observation times
                String durationStr = "00S";
                if (mFirstObsTime != null && mLastObsTime != null) {
                    long diff = mLastObsTime.toRoughMillis() - mFirstObsTime.toRoughMillis();
                    if (diff < 0)
                        diff = 0;
                    if (diff >= 86400000L) {
                        int days = (int) Math.round(diff / 86400000.0);
                        durationStr = String.format(Locale.US, "%02dD", days);
                    } else if (diff >= 3600000L) {
                        int hrs = (int) Math.round(diff / 3600000.0);
                        durationStr = String.format(Locale.US, "%02dH", hrs);
                    } else if (diff >= 60000L) {
                        int mins = (int) Math.round(diff / 60000.0);
                        durationStr = String.format(Locale.US, "%02dM", mins);
                    } else {
                        int secs = (int) Math.round(diff / 1000.0);
                        if (secs == 0)
                            secs = 1;
                        durationStr = String.format(Locale.US, "%02dS", secs);
                    }
                }

                // compute final file name using stored components
                String finalName = String.format(Locale.US, "%s_%s_%s_%s_%s_%s.rnx",
                        mStationName, mSource, mStartTimeStr, durationStr, mFru, mType);
                File finalFile = new File(mRinexFile.getParentFile(), finalName);

                BufferedWriter finalWriter = new BufferedWriter(new FileWriter(finalFile));
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
                // update reference to final file
                mRinexFile = finalFile;
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
        if (!mIsLogging || mBodyWriter == null)
            return;

        GnssClock clock = event.getClock();

        int discontinuityCount = clock.getHardwareClockDiscontinuityCount();
        if (mLastHwClockDiscontinuityCount == -1 || discontinuityCount != mLastHwClockDiscontinuityCount) {
            mLastHwClockDiscontinuityCount = discontinuityCount;
            mRefFullBiasNanos = clock.getFullBiasNanos();
            mRefBiasNanos = clock.hasBiasNanos() ? clock.getBiasNanos() : 0.0;
        }

        if (!mFirstObsSet) {
            long timeNanos = clock.getTimeNanos();
            // Calculate with high precision
            mFirstObsTime = calculateRinexTime(timeNanos, mRefFullBiasNanos, mRefBiasNanos);
            mFirstObsSet = true;
        }

        processEpoch(clock, event.getMeasurements());
    }

    private void processEpoch(GnssClock clock, Iterable<GnssMeasurement> measurements) {
        long timeNanos = clock.getTimeNanos();
        // --- MODIFIED: High Precision Time Calculation ---
        RinexTime epochTime = calculateRinexTime(timeNanos, mRefFullBiasNanos, mRefBiasNanos);
        // -------------------------------------------------

        long currentEpochMillis = epochTime.toRoughMillis();

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
            if (sysId == -1)
                continue;

            String rawCodeType = "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (m.hasCodeType()) {
                    rawCodeType = m.getCodeType();
                }
            }
            String signalName = getSmartSignalCode(sysId, m.getCarrierFrequencyHz(), rawCodeType);

            if (signalName == null || signalName.isEmpty())
                continue;

            if (sysId == SYS_GLO) {
                int svid = m.getSvid();
                Integer k = calculateGlonassSlot(m.getCarrierFrequencyHz());
                if (k != null) {
                    mGlonassFreqMap.put(svid, k);
                }
            }

            int freqIndex = registerSignal(sysId, signalName);
            if (freqIndex == -1)
                continue;

            if (!isMeasurementValid(m, sysId, signalName))
                continue;

            double rawCarrierFreqHz = m.getCarrierFrequencyHz();
            if (rawCarrierFreqHz == 0)
                continue;

            double nominalFreq = getNominalFrequency(sysId, rawCarrierFreqHz, m.getSvid());
            double wavl = CLIGHT / nominalFreq;

            // Pseudorange calculation uses raw nanos, precision is preserved
            double prSeconds = calculatePseudorangeSeconds(clock, m, sysId, mRefFullBiasNanos, mRefBiasNanos);
            if (prSeconds < 0 || prSeconds > 0.5)
                continue;

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

            sat.lli[freqIndex] = 0;
            if ((adrState & ADR_STATE_HALF_CYCLE_REPORTED) != 0 && (adrState & ADR_STATE_HALF_CYCLE_RESOLVED) == 0) {
                sat.lli[freqIndex] |= LLI_HALFC;
            }
            if ((adrState & ADR_STATE_RESET) != 0 || (adrState & ADR_STATE_CYCLE_SLIP) != 0) {
                sat.lli[freqIndex] |= LLI_SLIP;
            }
        }

        // Galileo 4ms correction
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
                    if (prevSat == null)
                        continue;

                    for (int i = 0; i < MAX_FRQ; i++) {
                        double pCurr = sat.p[i];
                        double pPrev = prevSat.p[i];

                        if (pCurr != 0 && pPrev != 0) {
                            if (Math.abs(pCurr - pPrev - range4ms) < threshold
                                    || Math.abs(pCurr - pPrev + range4ms) < threshold) {
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
                // update last observation time (used later to compute file duration)
                mLastObsTime = epochTime;
                writeEpoch(epochTime, epochSats);
                mPreviousEpochSats = epochSats;
                mPreviousEpochTimeMillis = currentEpochMillis;
            } catch (IOException e) {
                Log.e(TAG, "Error writing epoch", e);
            }
        }
    }

    // --- MODIFIED: High Precision Time Calculator (No Data Objects) ---
    private RinexTime calculateRinexTime(long timeNanos, long fullBiasNanos, double biasNanos) {
        // 1. Calculate rough GPS time in millis for Calendar (Year/Month/Day)
        long gpsTimeNanos = timeNanos - fullBiasNanos - (long) biasNanos;
        long gpsTimeMillis = gpsTimeNanos / 1000000L;
        long gpsEpochMillis = 315964800000L; // Jan 6 1980
        long rinexTimeMillis = gpsEpochMillis + gpsTimeMillis;

        // 2. Use Calendar for Date components
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(rinexTimeMillis);

        int year = cal.get(java.util.Calendar.YEAR);
        int month = cal.get(java.util.Calendar.MONTH) + 1;
        int day = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int min = cal.get(java.util.Calendar.MINUTE);

        // 3. Calculate Precise Seconds manually from Nanos
        // Get the seconds part from Calendar (integer)
        int secondsInt = cal.get(java.util.Calendar.SECOND);
        // Calculate the fractional part from the remaining nanos
        // modulo 1,000,000,000 gives the nanosecond part of the second
        long nanosPart = gpsTimeNanos % 1000000000L;
        if (nanosPart < 0)
            nanosPart += 1000000000L; // Handle negative modulo safety

        // Combine: Integer Seconds + (Nanos / 1e9)
        double preciseSeconds = secondsInt + (nanosPart / 1.0e9);

        return new RinexTime(year, month, day, hour, min, preciseSeconds);
    }
    // ------------------------------------------------------------------

    private String getSmartSignalCode(int sys, double carrierFreqHz, String androidCodeType) {
        double freqMhz = Math.round(carrierFreqHz / 1e5) / 10.0;
        String rawCode = (androidCodeType == null) ? "" : androidCodeType;
        String bandId = "";
        String defaultAttr = "";

        if (sys == SYS_BDS && Math.abs(freqMhz - 1561.1) < 1.0) {
            bandId = "2";
            defaultAttr = "I";
        } else if (Math.abs(freqMhz - 1575.4) < 1.0 || (sys == SYS_GLO && freqMhz > 1590 && freqMhz < 1615)) {
            bandId = "1";
            defaultAttr = (sys == SYS_BDS) ? "P" : "C";
        } else if (Math.abs(freqMhz - 1176.4) < 1.0) {
            bandId = "5";
            defaultAttr = (sys == SYS_BDS) ? "P" : "Q";
        } else if (Math.abs(freqMhz - 1227.6) < 1.0 || (sys == SYS_GLO && freqMhz > 1230 && freqMhz < 1260)) {
            bandId = "2";
            defaultAttr = "C";
        } else if (Math.abs(freqMhz - 1207.1) < 1.0) {
            bandId = "7";
            defaultAttr = (sys == SYS_BDS) ? "I" : "Q";
        } else if (Math.abs(freqMhz - 1268.5) < 1.0) {
            bandId = "6";
            defaultAttr = "I";
        }

        if (bandId.isEmpty())
            return null;
        String finalAttr = rawCode.isEmpty() ? defaultAttr : rawCode;

        if (sys == SYS_BDS && "5".equals(bandId) && "Q".equals(finalAttr))
            finalAttr = "P";
        if ("1".equals(bandId) && "L".equals(finalAttr))
            return null;

        return bandId + finalAttr;
    }

    private Integer calculateGlonassSlot(double freq) {
        if (freq > 1.59e9)
            return (int) Math.round((freq - 1602.0e6) / 0.5625e6);
        if (freq > 1.23e9 && freq < 1.26e9)
            return (int) Math.round((freq - 1246.0e6) / 0.4375e6);
        return null;
    }

    private double getNominalFrequency(int sysId, double rawFreq, int svid) {
        if (sysId == SYS_GLO) {
            Integer k = mGlonassFreqMap.get(svid);
            if (k != null) {
                if (rawFreq > 1.5e9)
                    return 1602.0e6 + k * 0.5625e6;
                else
                    return 1246.0e6 + k * 0.4375e6;
            }
            return rawFreq;
        } else if (sysId == SYS_BDS) {
            if (Math.abs(rawFreq - 1561.098e6) < 1.0e6)
                return 1561.098e6;
        }
        return Math.round(rawFreq / 1000.0) * 1000.0;
    }

    /**
     * Modified to use integer arithmetic (long) for the subtraction of Rx and Tx time
     * to prevent floating point precision loss, matching the logic in the Python script.
     */
    private double calculatePseudorangeSeconds(GnssClock clock, GnssMeasurement m, int sysId, long refFullBiasNanos,
                                               double refBiasNanos) {

        long timeNanos = clock.getTimeNanos();
        double timeOffsetNanos = m.getTimeOffsetNanos();

        // 1. Calculate Rx Time in Nanoseconds (Integer)
        // Keep in long to preserve precision
        long gpsTimeNanos = timeNanos - refFullBiasNanos + (long) timeOffsetNanos;

        // 2. Get Tx Time in Nanoseconds (Integer)
        // Do not multiply by 1e-9 here
        long tTxNanos = m.getReceivedSvTimeNanos();

        long weekNanos = 604800L * 1000000000L;
        long dayNanos = 86400L * 1000000000L;

        long tRxModNanos = 0;

        // 3. Align Rx Time to the same domain as Tx (TOW or TOD) using modulo
        if (sysId == SYS_GPS || sysId == SYS_GAL || sysId == SYS_QZS || sysId == SYS_BDS) {
            long timeOfWeekNanos = gpsTimeNanos % weekNanos;
            if (sysId == SYS_BDS) {
                // BDS offset 14s
                timeOfWeekNanos = (gpsTimeNanos - 14000000000L) % weekNanos;
            }
            tRxModNanos = timeOfWeekNanos;
        } else if (sysId == SYS_GLO) {
            long timeOfDayNanos = gpsTimeNanos % dayNanos;
            // GLONASS offset: UTC+3h - LeapSecond
            long gloOffsetNanos = (3 * 3600 - LEAP_SECOND) * 1000000000L;
            tRxModNanos = timeOfDayNanos + gloOffsetNanos;
        }

        // 4. Calculate Flight Time in Nanoseconds (Rx - Tx)
        // Crucial Step: This subtraction happens in 'long', preserving sub-nanosecond precision
        // relative to the bias.
        long flightTimeNanos = tRxModNanos - tTxNanos;

        // 5. Handle Rollovers (Week/Day crossovers) on the difference
        // Thresholds in nanoseconds
        long halfWeekNanos = 302400L * 1000000000L;
        long halfDayNanos = 43200L * 1000000000L;

        if (sysId != SYS_GLO) {
            if (flightTimeNanos > halfWeekNanos) {
                flightTimeNanos -= weekNanos;
            } else if (flightTimeNanos < -halfWeekNanos) {
                flightTimeNanos += weekNanos;
            }
        } else {
            // GLONASS Day Rollover
            if (flightTimeNanos > halfDayNanos) {
                flightTimeNanos -= dayNanos;
            } else if (flightTimeNanos < -halfDayNanos) {
                flightTimeNanos += dayNanos;
            }
        }

        // 6. Final Calculation: Apply Bias and Convert to Seconds
        // Now flightTimeNanos is small (approx 0.07s in nanos), so double precision is sufficient.
        // Formula: (FlightTime - Bias) * 1e-9
        double pr = (flightTimeNanos - refBiasNanos) * 1e-9;

        // 7. Final Sanity Check (Legacy logic preserved for safety)
        // If rollover correction above worked, this should be redundant but safe
        if ((sysId == SYS_GPS || sysId == SYS_GAL || sysId == SYS_BDS || sysId == SYS_QZS) && pr > 604800)
            pr %= 604800.0;
        if (sysId == SYS_GLO && pr > 86400)
            pr %= 86400.0;

        return pr;
    }

    private boolean isMeasurementValid(GnssMeasurement m, int sysId, String signalName) {
        int state = m.getState();
        if ((state & STATE_MSEC_AMBIGUOUS) != 0)
            return false;

        boolean towDecoded = false;
        if (sysId == SYS_GLO)
            towDecoded = (state & STATE_GLO_TOD_DECODED) != 0;
        else
            towDecoded = (state & STATE_TOW_DECODED) != 0;
        if (!towDecoded)
            return false;

        boolean codeLock = false;
        if (sysId == SYS_GAL && "1C".equals(signalName)) {
            codeLock = (state & STATE_GAL_E1BC_CODE_LOCK) != 0 || (state & STATE_GAL_E1C_2ND_CODE_LOCK) != 0;
        } else {
            codeLock = (state & STATE_CODE_LOCK) != 0;
        }
        if (!codeLock)
            return false;

        if (m.getPseudorangeRateUncertaintyMetersPerSecond() > MAXPRRUNCMPS)
            return false;
        if (m.getReceivedSvTimeUncertaintyNanos() > MAXTOWUNCNS)
            return false;
        if (m.getAccumulatedDeltaRangeUncertaintyMeters() > MAXADRUNCNS)
            return false;

        return true;
    }

    private int registerSignal(int sys, String sig) {
        int sysIdx = getSystemIndex(sys);
        if (sysIdx == -1)
            return -1;
        for (int i = 0; i < mNumSignals[sysIdx]; i++) {
            if (mSignals[sysIdx][i].equals(sig))
                return i;
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
            if (s.sys == sys && s.prn == prn)
                return s;
        }
        RnxSat newSat = new RnxSat(sys, prn);
        sats.add(newSat);
        return newSat;
    }

    private void writeEpoch(RinexTime t, List<RnxSat> sats) throws IOException {
        List<RnxSat> validSats = new ArrayList<>();
        for (RnxSat sat : sats) {
            boolean allZero = true;
            for (int i = 0; i < MAX_FRQ; i++) {
                if (Math.abs(sat.p[i]) > NEAR_ZERO || Math.abs(sat.l[i]) > NEAR_ZERO) {
                    allZero = false;
                    break;
                }
            }
            if (!allZero)
                validSats.add(sat);
        }

        Collections.sort(validSats, new Comparator<RnxSat>() {
            @Override
            public int compare(RnxSat o1, RnxSat o2) {
                int p1 = getSystemPriority(o1.sys);
                int p2 = getSystemPriority(o2.sys);
                if (p1 != p2)
                    return Integer.compare(p1, p2);
                return Integer.compare(o1.prn, o2.prn);
            }
        });

        // --- MODIFIED: Use Precise Seconds ---
        mBodyWriter.write(String.format(Locale.US, "> %04d %02d %02d %02d %02d %10.7f  0 %2d",
                t.year, t.month, t.day, t.hour, t.min, t.sec, validSats.size()));
        mBodyWriter.newLine();
        // -------------------------------------

        for (RnxSat sat : validSats) {
            char sysChar = getSystemChar(sat.sys);
            int prn = sat.prn;
            if (sat.sys == SYS_QZS)
                prn -= 192;

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
        if (Math.abs(val) < NEAR_ZERO)
            return "                ";
        return String.format(Locale.US, "%14.3f  ", val);
    }

    private String formatPhase(double val, int lli) {
        if (Math.abs(val) < NEAR_ZERO)
            return "                ";
        String lliStr = (lli == 0) ? " " : String.valueOf(lli);
        return String.format(Locale.US, "%14.3f%s ", val, lliStr);
    }

    private void writeHeader(BufferedWriter writer) throws IOException {
        writer.write("     3.05           OBSERVATION DATA    M: Mixed            RINEX VERSION / TYPE\n");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd HHmmss", Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String dateStr = sdf.format(new Date()) + " UTC";
        String pgm = "GeoLog";
        String runBy = Build.MANUFACTURER;
        if (runBy.length() > 20)
            runBy = runBy.substring(0, 20);
        String receiverNumber = fitField(mReceiverNumber, 20, "Unknown");
        String receiverType = fitField(mReceiverType, 20, Build.MANUFACTURER + " " + Build.MODEL);
        String receiverVersion = fitField(mReceiverVersion, 20, Build.VERSION.RELEASE);
        String antennaNumber = fitField(mAntennaNumber, 20, "unknown");
        String antennaType = fitField(mAntennaType, 40, "unknown");
        String observer = fitField(mObserver, 20, "SWJTU");
        String agency = fitField(mAgency, 40, "SWJTU");

        writer.write(String.format(Locale.US, "%-20s%-20s%-20sPGM / RUN BY / DATE   \n", pgm, runBy, dateStr));
        writer.write(String.format(Locale.US, "%-60sMARKER NAME         \n", mMarkerName));
        writer.write(String.format(Locale.US, "%-60sMARKER NUMBER       \n", mMarkerNumber));
        writer.write(String.format(Locale.US, "%-60sMARKER TYPE         \n", mMarkerType));
        writer.write(String.format(Locale.US, "%-20s%-40sOBSERVER / AGENCY   \n", observer, agency));
        writer.write(String.format(Locale.US, "%-20s%-20s%-20sREC # / TYPE / VERS \n",
                receiverNumber, receiverType, receiverVersion));
        writer.write(String.format(Locale.US, "%-20s%-40sANT # / TYPE        \n", antennaNumber, antennaType));
        writer.write(String.format(Locale.US, "%14.4f%14.4f%14.4f                  APPROX POSITION XYZ \n",
                mApproxPos[0], mApproxPos[1], mApproxPos[2]));
        writer.write(String.format(Locale.US, "%14.4f%14.4f%14.4f                  ANTENNA: DELTA H/E/N\n",
            mAntennaDeltaH, mAntennaDeltaE, mAntennaDeltaN));

        char[] sysChars = { 'G', 'R', 'E', 'C', 'J' };
        int[] sysIds = { SYS_GPS, SYS_GLO, SYS_GAL, SYS_BDS, SYS_QZS };

        for (int k = 0; k < 5; k++) {
            int sys = sysIds[k];
            int idx = getSystemIndex(sys);
            if (mNumSignals[idx] > 0) {
                List<String> codes = new ArrayList<>();
                for (int i = 0; i < mNumSignals[idx]; i++) {
                    String suf = mSignals[idx][i];
                    codes.add("C" + suf);
                    codes.add("L" + suf);
                    codes.add("D" + suf);
                    codes.add("S" + suf);
                }

                int nObs = codes.size();
                List<String> firstBatch = codes.subList(0, Math.min(codes.size(), 13));
                StringBuilder sb = new StringBuilder();
                for (String c : firstBatch)
                    sb.append(String.format("%-4s", c));

                writer.write(String.format(Locale.US, "%c  %3d %-52s SYS / # / OBS TYPES \n", sysChars[k], nObs,
                        sb.toString()));

                for (int i = 13; i < codes.size(); i += 13) {
                    List<String> batch = codes.subList(i, Math.min(codes.size(), i + 13));
                    sb = new StringBuilder();
                    for (String c : batch)
                        sb.append(String.format("%-4s", c));
                    writer.write(String.format(Locale.US, "       %-52s SYS / # / OBS TYPES \n", sb.toString()));
                }
            }
        }

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
            // --- MODIFIED: Use Precise Time for Header ---
            writer.write(String.format(Locale.US,
                    "  %04d    %02d    %02d    %02d    %02d   %10.7f     GPS         TIME OF FIRST OBS\n",
                    mFirstObsTime.year, mFirstObsTime.month, mFirstObsTime.day,
                    mFirstObsTime.hour, mFirstObsTime.min, mFirstObsTime.sec));
            // ---------------------------------------------
        }

        writer.write("                                                            END OF HEADER       \n");
    }

    private int getSystemId(int constType) {
        switch (constType) {
            case GnssStatus.CONSTELLATION_GPS:
                return SYS_GPS;
            case GnssStatus.CONSTELLATION_GLONASS:
                return SYS_GLO;
            case GnssStatus.CONSTELLATION_BEIDOU:
                return SYS_BDS;
            case GnssStatus.CONSTELLATION_GALILEO:
                return SYS_GAL;
            case GnssStatus.CONSTELLATION_QZSS:
                return SYS_QZS;
            default:
                return -1;
        }
    }

    private int getSystemIndex(int sys) {
        if (sys == SYS_GPS)
            return 0;
        if (sys == SYS_GLO)
            return 1;
        if (sys == SYS_GAL)
            return 2;
        if (sys == SYS_BDS)
            return 3;
        if (sys == SYS_QZS)
            return 4;
        return -1;
    }

    private char getSystemChar(int sys) {
        if (sys == SYS_GPS)
            return 'G';
        if (sys == SYS_GLO)
            return 'R';
        if (sys == SYS_GAL)
            return 'E';
        if (sys == SYS_BDS)
            return 'C';
        if (sys == SYS_QZS)
            return 'J';
        return ' ';
    }

    private int getSystemPriority(int sys) {
        if (sys == SYS_GPS)
            return 1;
        if (sys == SYS_GLO)
            return 2;
        if (sys == SYS_GAL)
            return 3;
        if (sys == SYS_BDS)
            return 4;
        return 5;
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
        return new double[] { x, y, z };
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