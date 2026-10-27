/*  Copyright (C) 2025 idowatch / TOOBUR device support */
package nodomain.freeyourgadget.gadgetbridge.service.devices.toobur;

import android.content.Context;

import java.util.Calendar;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.id115.ID115SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.ID115ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

/**
 * Persists live/sync activity data into {@link ID115ActivitySample} so the device card
 * (DailyTotals → step/distance mini charts) and heart-rate charts receive data.
 */
public final class TooburActivityDatabaseSync {
    private TooburActivityDatabaseSync() {
    }

    /** Local midnight start-of-day, seconds since epoch. */
    public static int localDayStartTs() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return (int) (cal.getTimeInMillis() / 1000L);
    }

    static int roundDownToMinuteTs(int tsSeconds) {
        return (tsSeconds / 60) * 60;
    }

    /**
     * Writes one “today” row (cumulative steps/distance at local midnight timestamp) and,
     * when HR is valid, a separate per-minute sample with HR only (steps 0) for the HR graph.
     * <p>
     * Matches GET live data layout: Steps, MinutesRecordingLiveDataThisDay, Distance, GoalsHit, LastKnownHRM (1 byte).
     * Minutes are stored in {@link ID115ActivitySample#setActiveTimeMinutes(Integer)}. Calories are not in this
     * packet; the daily row leaves them unset.
     * <p>
     * {@code GoalsHit} (u32 in the live reply) is decoded in {@link TooburSupport} for logging only — it is not
     * persisted on {@link ID115ActivitySample} (no dedicated field).
     */
    public static void persistLiveData(Context context, GBDevice device, int steps, int distanceMeters,
            int minutesRecordingLiveDataThisDay, int lastKnownHrmBpm) {
        if (device == null) {
            return;
        }
        Context ctx = context != null ? context : GBApplication.getContext();
        try (DBHandler db = GBApplication.acquireDB()) {
            long userId = DBHelper.getUser(db.getDaoSession()).getId();
            long deviceId = DBHelper.getDevice(device, db.getDaoSession()).getId();
            ID115SampleProvider provider = new ID115SampleProvider(device, db.getDaoSession());

            int dayStart = localDayStartTs();
            ID115ActivitySample day = new ID115ActivitySample();
            day.setTimestamp(dayStart);
            day.setUserId(userId);
            day.setDeviceId(deviceId);
            day.setRawKind(ActivityKind.ACTIVITY.getCode());
            day.setSteps(Math.max(0, steps));
            day.setCaloriesBurnt(null);
            day.setDistanceMeters(distanceMeters > 0 ? distanceMeters : null);
            day.setActiveTimeMinutes(minutesRecordingLiveDataThisDay > 0 ? minutesRecordingLiveDataThisDay : null);
            day.setHeartRate(ActivitySample.NOT_MEASURED);

            provider.addGBActivitySamples(java.util.Collections.singletonList(day));

            HeartRateUtils hr = HeartRateUtils.getInstance();
            if (hr.isValidHeartRateValue(lastKnownHrmBpm)) {
                int now = (int) (System.currentTimeMillis() / 1000L);
                int minuteTs = roundDownToMinuteTs(now);
                if (minuteTs <= dayStart) {
                    minuteTs = dayStart + 60;
                }
                ID115ActivitySample hrRow = new ID115ActivitySample();
                hrRow.setTimestamp(minuteTs);
                hrRow.setUserId(userId);
                hrRow.setDeviceId(deviceId);
                hrRow.setRawKind(ActivityKind.ACTIVITY.getCode());
                hrRow.setSteps(0);
                hrRow.setHeartRate(lastKnownHrmBpm);
                provider.addGBActivitySamples(java.util.Collections.singletonList(hrRow));
            }

            device.sendDeviceUpdateIntent(ctx);
        } catch (Exception ignored) {
        }
    }
}
