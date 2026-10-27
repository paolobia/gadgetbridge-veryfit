/*  Copyright (C) 2025 idowatch / TOOBUR device support */
package nodomain.freeyourgadget.gadgetbridge.service.devices.toobur;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;

import android.content.SharedPreferences;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.id115.ID115SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.ID115ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.id115.AbstractID115Operation;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * VeryFit v3 health sync: cmd {@code 0x05} (sizes) then per-type cmd {@code 0x04} start/stop.
 * Matches {@code packetdumps/logcat/sync_example.txt} and {@link TooburV3HealthSync}.
 */
public class TooburV3FetchHealthOperation extends AbstractID115Operation {
    private static final Logger LOG = LoggerFactory.getLogger(TooburV3FetchHealthOperation.class);

    private enum Phase { WAIT_SIZES, WAIT_START_ACK, WAIT_STOP_ACK }

    private final TooburV3HealthSync.V3ReassemblyBuffer rxBuf = new TooburV3HealthSync.V3ReassemblyBuffer();
    private Phase phase = Phase.WAIT_SIZES;
    private int currentTypeIndex;
    /** Subset of {@link TooburV3HealthSync#V3_HEALTH_SYNC_DATA_TYPES} from device prefs (pull-down sync). */
    private int[] activeSyncTypes = new int[0];
    private final boolean autoFetch;

    private final TooburV3HealthSync.ReplyListener persistListener = new TooburV3HealthSync.ReplyListener() {
        @Override
        public void onV3HealthSizesTotal(int totalBytes) {
            // logged in state machine
        }

        @Override
        public void onV3Spo2Parsed(TooburV3DayMetricParser.ParsedDayMetric parsed, int dataSizeBytes) {
            persistDayMetric(parsed, true);
        }

        @Override
        public void onV3PressureParsed(TooburV3DayMetricParser.ParsedDayMetric parsed, int dataSizeBytes) {
            persistDayMetric(parsed, false);
        }

        @Override
        public void onV3SportSummary(TooburV3HealthSync.V3SportSummary summary) {
            persistSportSummary(summary);
        }
    };

    public TooburV3FetchHealthOperation(TooburSupport support, boolean autoFetch) {
        super(support);
        this.autoFetch = autoFetch;
    }

    private TooburSupport tb() {
        return (TooburSupport) getSupport();
    }

    @Override
    protected boolean isHealthOperation() {
        return true;
    }

    @Override
    protected void doPerform() throws IOException {
        phase = Phase.WAIT_SIZES;
        currentTypeIndex = 0;
        rxBuf.reset();
        activeSyncTypes = TooburSupport.getEnabledV3HealthSyncDataTypes(
                GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()), autoFetch);
        if (activeSyncTypes.length == 0) {
            LOG.warn("[V3 fetch] no health types enabled in settings — sync types step will be skipped");
        }

        int seq = tb().peekV3Seq();
        int[] offsets = buildOffsetsForHealthSizesProbe();
        byte[] sizes = TooburV3HealthSync.buildV3HealthSizesRequest(seq, offsets);
        tb().consumeV3SeqSlot(); // reserve seq used above

        TransactionBuilder builder = performInitialized("v3_health_sizes");
        queueChunkedHealth(builder, sizes);
        builder.queue();
    }

    private void queueChunkedHealth(TransactionBuilder builder, byte[] fullFrame) {
        int payload = AbstractBTLEDeviceSupport.calcMaxWriteChunk(tb().getMTU());
        java.util.List<byte[]> chunks = TooburV3BleChunkedWrite.splitForAttMtu(fullFrame, payload);
        for (int i = 0; i < chunks.size(); i++) {
            builder.write(controlCharacteristic, chunks.get(i));
            if (i + 1 < chunks.size()) {
                builder.wait(TooburV3BleChunkedWrite.CHUNK_GAP_MS);
            }
        }
    }

    @Override
    protected void handleResponse(byte[] data) {
        if (!isOperationRunning()) {
            return;
        }
        if (!TooburV3HealthSync.feedNotifyFragmentRx(rxBuf, data)) {
            return;
        }
        byte[] complete;
        while ((complete = rxBuf.pollComplete()) != null) {
            handleCompleteFrame(complete);
            TooburV3HealthSync.dispatchV3Reply(complete, persistListener);
        }
    }

    private void handleCompleteFrame(byte[] frame) {
        if (frame.length < 12) {
            return;
        }
        int cmd = u16le(frame, 7);

        if (cmd == TooburV3HrPackets.V3_CMD_HR_UNIFIED) {
            TooburV3HrPackets.logV3Hr09StrippedRx(frame, LOG);
            return;
        }

        if (phase == Phase.WAIT_SIZES && cmd == TooburV3HealthSync.V3_CMD_HEALTH_SIZES) {
            int total = TooburV3HealthSync.parseV3HealthSizesReplyTotal(frame);
            LOG.info("[V3 fetch] sizes reply totalBytes={}", total);
            SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
            boolean sportProbe = prefs.getBoolean(TooburSupport.PREF_TOOBUR_V3_HEALTH_SPORT_OFFSET_PROBE, false);
            String totalKey = sportProbe
                    ? TooburSupport.PREF_TOOBUR_V3_HEALTH_LAST_SPORT_PROBE_TOTAL
                    : TooburSupport.PREF_TOOBUR_V3_HEALTH_LAST_TOTAL;
            long previous = prefs.getLong(totalKey, -1L);
            prefs.edit().putLong(totalKey, (long) total & 0xFFFFFFFFL).apply();

            boolean forceFull = prefs.getBoolean(TooburSupport.PREF_TOOBUR_V3_HEALTH_FETCH_FORCE_FULL, false);
            if (!forceFull && total == 0) {
                LOG.info("[V3 fetch] total=0, nothing to sync");
                operationFinished();
                return;
            }
            if (!forceFull && previous >= 0 && total <= previous) {
                LOG.info("[V3 fetch] total not increased ({} <= {}), skip 0x04", total, previous);
                operationFinished();
                return;
            }

            if (activeSyncTypes.length == 0) {
                LOG.info("[V3 fetch] no types selected for sync — done after sizes");
                operationFinished();
                return;
            }

            phase = Phase.WAIT_START_ACK;
            sendSyncStartForCurrentType();
            return;
        }

        if (cmd != TooburV3HealthSync.V3_CMD_HEALTH_SYNC) {
            return;
        }

        if (phase == Phase.WAIT_START_ACK) {
            phase = Phase.WAIT_STOP_ACK;
            sendSyncStopForCurrentType();
            return;
        }
        if (phase == Phase.WAIT_STOP_ACK) {
            currentTypeIndex++;
            if (currentTypeIndex >= activeSyncTypes.length) {
                LOG.info("[V3 fetch] all types done");
                operationFinished();
                return;
            }
            phase = Phase.WAIT_START_ACK;
            sendSyncStartForCurrentType();
        }
    }

    private void sendSyncStartForCurrentType() {
        try {
            int dataType = activeSyncTypes[currentTypeIndex];
            int seq = tb().nextV3Seq();
            byte[] pkt = TooburV3HealthSync.buildHealthSync04(
                    TooburV3HealthSync.V3_HEALTH_OPERATE_START,
                    dataType,
                    seq,
                    TooburV3HealthSync.defaultByte14ForDataType(dataType),
                    0);
            TransactionBuilder b = performInitialized("v3_sync_start_" + dataType);
            queueChunkedHealth(b, pkt);
            b.queue();
            LOG.debug("[V3 fetch] START type=0x{} seq=0x{}", Integer.toHexString(dataType), Integer.toHexString(seq));
        } catch (IOException e) {
            LOG.error("v3 sync start failed", e);
            GB.toast(getContext(), "V3 sync failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG, GB.ERROR, e);
            operationFinished();
        }
    }

    private void sendSyncStopForCurrentType() {
        try {
            int dataType = activeSyncTypes[currentTypeIndex];
            int seq = tb().nextV3Seq();
            byte[] pkt = TooburV3HealthSync.buildHealthSync04(
                    TooburV3HealthSync.V3_HEALTH_OPERATE_STOP,
                    dataType,
                    seq,
                    TooburV3HealthSync.defaultByte14ForDataType(dataType),
                    0);
            TransactionBuilder b = performInitialized("v3_sync_stop_" + dataType);
            queueChunkedHealth(b, pkt);
            b.queue();
            LOG.debug("[V3 fetch] STOP type=0x{} seq=0x{}", Integer.toHexString(dataType), Integer.toHexString(seq));
        } catch (IOException e) {
            LOG.error("v3 sync stop failed", e);
            GB.toast(getContext(), "V3 sync stop failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG, GB.ERROR, e);
            operationFinished();
        }
    }

    private static int u16le(byte[] b, int off) {
        if (b.length < off + 2) {
            return 0;
        }
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private int[] buildOffsetsForHealthSizesProbe() {
        SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        int[] offsets = new int[TooburV3HealthSync.V3_HEALTH_SYNC_DATA_TYPES.length];
        if (prefs.getBoolean(TooburSupport.PREF_TOOBUR_V3_HEALTH_SPORT_OFFSET_PROBE, false)) {
            offsets[6] = prefs.getInt(TooburSupport.PREF_TOOBUR_V3_HEALTH_SPORT_OFFSET, 0);
        }
        return offsets;
    }

    private void persistDayMetric(TooburV3DayMetricParser.ParsedDayMetric parsed, boolean spo2Column) {
        if (parsed == null || parsed.points.isEmpty()) {
            return;
        }
        try (DBHandler db = GBApplication.acquireDB()) {
            long userId = DBHelper.getUser(db.getDaoSession()).getId();
            long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
            ID115SampleProvider provider = new ID115SampleProvider(getDevice(), db.getDaoSession());
            List<ID115ActivitySample> batch = new ArrayList<>(parsed.points.size());
            for (TooburV3DayMetricParser.MetricPoint p : parsed.points) {
                long ms = TooburV3DayMetricParser.toEpochMillis(p.year, p.month, p.day, p.secondOfDay);
                int ts = (int) (ms / 1000L);
                ID115ActivitySample s = new ID115ActivitySample();
                s.setTimestamp(ts);
                s.setUserId(userId);
                s.setDeviceId(deviceId);
                s.setRawKind(ActivityKind.ACTIVITY.getCode());
                s.setSteps(0);
                s.setHeartRate(ActivitySample.NOT_MEASURED);
                if (spo2Column) {
                    s.setSpo2(p.value);
                    s.setStress(null);
                } else {
                    s.setStress(p.value);
                    s.setSpo2(null);
                }
                batch.add(s);
            }
            provider.addGBActivitySamples(batch);
            LOG.info("[V3 fetch] stored {} {} point(s) ({})", batch.size(),
                    spo2Column ? "SpO2" : "stress", parsed.parsingModel);
        } catch (Exception ex) {
            LOG.error("Failed to store v3 SpO2/stress samples", ex);
            GB.toast(getContext(), "V3 sync: DB error " + ex.getMessage(), android.widget.Toast.LENGTH_LONG, GB.ERROR, ex);
        }
    }

    private void persistSportSummary(TooburV3HealthSync.V3SportSummary s) {
        if (s.totalSteps <= 0) {
            return;
        }
        Calendar calendar = new GregorianCalendar(s.year, s.month - 1, s.day);
        int ts = (int) (calendar.getTimeInMillis() / 1000);

        ID115ActivitySample sample = new ID115ActivitySample();
        sample.setTimestamp(ts);
        sample.setRawKind(ActivityKind.ACTIVITY.getCode());
        sample.setSteps(s.totalSteps);
        sample.setDistanceMeters(Math.max(0, s.totalDistance / 100));
        sample.setCaloriesBurnt(s.totalCalories);
        sample.setActiveTimeMinutes(s.totalActiveTime > 0 ? s.totalActiveTime / 60 : 0);
        sample.setHeartRate(ActivitySample.NOT_MEASURED);

        try (DBHandler db = GBApplication.acquireDB()) {
            long userId = DBHelper.getUser(db.getDaoSession()).getId();
            long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
            sample.setUserId(userId);
            sample.setDeviceId(deviceId);
            ID115SampleProvider provider = new ID115SampleProvider(getDevice(), db.getDaoSession());
            provider.addGBActivitySamples(java.util.Collections.singletonList(sample));
            LOG.info("[V3 fetch] stored sport sample steps={} date={}-{}-{}", s.totalSteps, s.year, s.month, s.day);
        } catch (Exception ex) {
            LOG.error("Failed to store v3 sport sample", ex);
            GB.toast(getContext(), "V3 sync: DB error " + ex.getMessage(), android.widget.Toast.LENGTH_LONG, GB.ERROR, ex);
        }
    }
}
