/*  Copyright (C) 2025 idowatch / TOOBUR device support

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.toobur;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.id115.ID115Constants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.GenericItem;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.id115.ID115Support;

/**
 * TOOBUR device support: extends ID115 with battery (level + voltage), device info,
 * live data (steps/HR), find device/phone, music control, and device-specific settings.
 * GET replies and live data are received on 0x0AF7.
 */
public class TooburSupport extends ID115Support {
    private static final Logger LOG = LoggerFactory.getLogger(TooburSupport.class);

    /** Preference key: music on device enabled (SET 0x03 0x2A). */
    public static final String PREF_TOOBUR_MUSIC_ENABLED = "toobur_music_enabled";
    /** Preference key: call/notification alert enabled (SET 0x03 0x30). */
    public static final String PREF_TOOBUR_CALL_ALERT_ENABLED = "toobur_call_alert_enabled";
    /** Preference key: do not disturb enabled (SET 0x03 0x29). */
    public static final String PREF_TOOBUR_DND_ENABLED = "toobur_dnd_enabled";
    /** Preference key: raise-to-wake / up hand gesture (SET 0x03 0x28). */
    public static final String PREF_TOOBUR_RAISE_TO_WAKE = "toobur_raise_to_wake";
    /** Preference key: continuous HR on/off (v3 cmd {@code 0x09} on 0x0AF6; state {@code CC}/{@code AA}). */
    public static final String PREF_TOOBUR_HR_CONTINUOUS_ENABLED = "toobur_hr_continuous_enabled";
    /** Preference key: continuous SpO₂ on band (SET {@code 0x03 0x44}; on {@code 0xAA} / off {@code 0x55}). */
    public static final String PREF_TOOBUR_SPO2_CONTINUOUS_ENABLED = "toobur_spo2_continuous_enabled";
    /** Preference key: continuous stress/pressure on band (SET {@code 0x03 0x45}; on {@code 0xAA} / off {@code 0x55}). */
    public static final String PREF_TOOBUR_PRESSURE_CONTINUOUS_ENABLED = "toobur_pressure_continuous_enabled";
    /**
     * Auto activity / sport detection (SET {@code 0x03 0x49}) — see {@link TooburAutoActivitySwitchPackets}.
     * Values {@link TooburAutoActivitySwitchPackets#PRESET_OFF}, {@link TooburAutoActivitySwitchPackets#PRESET_WALK_RUN}, …
     */
    public static final String PREF_TOOBUR_AUTO_ACTIVITY_PRESET = "toobur_auto_activity_preset";
    /** Preference key: measurement interval for v3 cmd {@code 0x09} when continuous is on — {@code 5,60,180,300,600,900,1800} or {@code 255} (smart). */
    public static final String PREF_TOOBUR_HR_INTERVAL_SECONDS = "toobur_hr_interval_seconds";
    /** Legacy list pref (removed from UI); used only to migrate to {@link #PREF_TOOBUR_HR_CONTINUOUS_ENABLED}. */
    private static final String LEGACY_PREF_TOOBUR_HR_MODE = "toobur_heart_rate_mode";
    /** Legacy boolean: include v3 health type in fetch (migrated to {@code *_mode} list prefs). */
    public static final String PREF_TOOBUR_V3_SYNC_SPO2 = "toobur_v3_sync_spo2";
    public static final String PREF_TOOBUR_V3_SYNC_PRESSURE = "toobur_v3_sync_pressure";
    public static final String PREF_TOOBUR_V3_SYNC_HR_DAY = "toobur_v3_sync_hr_day";
    public static final String PREF_TOOBUR_V3_SYNC_ACTIVITY = "toobur_v3_sync_activity";
    public static final String PREF_TOOBUR_V3_SYNC_SWIM = "toobur_v3_sync_swim";
    public static final String PREF_TOOBUR_V3_SYNC_SLEEP = "toobur_v3_sync_sleep";
    public static final String PREF_TOOBUR_V3_SYNC_SPORT = "toobur_v3_sync_sport";
    /** Values {@link #V3_SYNC_MODE_OFF}, {@link #V3_SYNC_MODE_MANUAL}, {@link #V3_SYNC_MODE_BOTH}. */
    public static final String PREF_TOOBUR_V3_SYNC_SPO2_MODE = "toobur_v3_sync_spo2_mode";
    public static final String PREF_TOOBUR_V3_SYNC_PRESSURE_MODE = "toobur_v3_sync_pressure_mode";
    public static final String PREF_TOOBUR_V3_SYNC_HR_DAY_MODE = "toobur_v3_sync_hr_day_mode";
    public static final String PREF_TOOBUR_V3_SYNC_ACTIVITY_MODE = "toobur_v3_sync_activity_mode";
    public static final String PREF_TOOBUR_V3_SYNC_SWIM_MODE = "toobur_v3_sync_swim_mode";
    public static final String PREF_TOOBUR_V3_SYNC_SLEEP_MODE = "toobur_v3_sync_sleep_mode";
    public static final String PREF_TOOBUR_V3_SYNC_SPORT_MODE = "toobur_v3_sync_sport_mode";
    public static final String V3_SYNC_MODE_OFF = "off";
    public static final String V3_SYNC_MODE_MANUAL = "manual";
    public static final String V3_SYNC_MODE_BOTH = "both";
    /** GET 0x02 0xA0 live steps/HR: same mode scheme as v3 types. */
    public static final String PREF_TOOBUR_LIVE_DATA_FETCH_MODE = "toobur_live_data_fetch_mode";
    /** Participate in Gadgetbridge auto-fetch (unlock / background) for this device. */
    public static final String PREF_TOOBUR_AUTO_FETCH_ENABLED = "toobur_auto_fetch_enabled";
    /**
     * Minimum minutes between auto-fetches for this device; {@code 0} uses the app-wide
     * {@link nodomain.freeyourgadget.gadgetbridge.util.GBPrefs#PREF_AUTO_FETCH_INTERVAL_LIMIT}.
     */
    public static final String PREF_TOOBUR_AUTO_FETCH_INTERVAL_MINUTES = "toobur_auto_fetch_interval_minutes";
    /** Wall-clock millis when auto-fetch last ran for this device (device-specific prefs). */
    public static final String PREF_TOOBUR_AUTO_FETCH_LAST_MS = "toobur_auto_fetch_last_ms";
    /** Preference key: weather on watch (SET 0x03 0x2D). Only if func table weather bit is set. */
    public static final String PREF_TOOBUR_WEATHER_ENABLED = "toobur_weather_enabled";
    /** Device settings: tap to send BIND start (manual; not on connect). */
    public static final String PREF_TOOBUR_ACTION_SEND_BIND = "toobur_action_send_bind";
    /** Device settings: tap to send unbind (manual). */
    public static final String PREF_TOOBUR_ACTION_SEND_UNBIND = "toobur_action_send_unbind";

    /** Last aggregate size from v3 cmd {@code 0x05} when all type offsets are 0 (efficient-sync baseline). */
    public static final String PREF_TOOBUR_V3_HEALTH_LAST_TOTAL = "toobur_v3_health_last_total";
    /** When {@link #PREF_TOOBUR_V3_HEALTH_SPORT_OFFSET_PROBE} is true: compare {@code 0x05} totals to this key. */
    public static final String PREF_TOOBUR_V3_HEALTH_LAST_SPORT_PROBE_TOTAL = "toobur_v3_health_last_sport_probe_total";
    /** Byte offset into sport stream (u32) for sport-only {@code 0x05} probes. */
    public static final String PREF_TOOBUR_V3_HEALTH_SPORT_OFFSET = "toobur_v3_health_sport_offset";
    /** If true, {@code 0x05} uses offset 0 for types 01–07 and {@link #PREF_TOOBUR_V3_HEALTH_SPORT_OFFSET} for 08. */
    public static final String PREF_TOOBUR_V3_HEALTH_SPORT_OFFSET_PROBE = "toobur_v3_health_sport_offset_probe";
    /** If false, “fetch activity data” still runs {@code 0x05} but skips {@code 0x04} when total ≤ last stored. */
    public static final String PREF_TOOBUR_V3_HEALTH_FETCH_FORCE_FULL = "toobur_v3_health_fetch_force_full";

    /**
     * Monotonic v3 sequence (16-bit), reset each connection — matches VeryFit / {@code toobur-hr-csv.html}.
     */
    private int v3Seq = 0x2E;

    /**
     * VeryFit bind start (evt 200): {@code 04 01 F1 01 01 02 02 01 00} — see app_fresh_launch / TOOBUR.md.
     */
    private static final byte[] TOOBUR_BIND_START = new byte[]{
            ID115Constants.CMD_ID_BIND_UNBIND,
            0x01,
            (byte) 0xF1, 0x01, 0x01, 0x02, 0x02, 0x01, 0x00
    };

    /**
     * Unbind / bind end (symmetric key {@code 0x02}); same tail as bind — adjust if your capture differs.
     */
    private static final byte[] TOOBUR_UNBIND = new byte[]{
            ID115Constants.CMD_ID_BIND_UNBIND,
            0x02,
            (byte) 0xF1, 0x01, 0x01, 0x02, 0x02, 0x01, 0x00
    };

    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();
    private final GBDeviceEventVersionInfo versionCmd = new GBDeviceEventVersionInfo();

    /** Set while a deferred {@code connectGatt} is scheduled (OEM discovery preference on). */
    private volatile boolean oemDeferredGattConnectPending;

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        normalWriteCharacteristic = getCharacteristic(ID115Constants.UUID_CHARACTERISTIC_WRITE_NORMAL);
        healthWriteCharacteristic = getCharacteristic(ID115Constants.UUID_CHARACTERISTIC_WRITE_HEALTH);

        v3Seq = 0x2E;

        builder.setDeviceState(GBDevice.State.INITIALIZING);

        // Enable notifications on 0x0AF7 so we receive GET replies (battery, device info)
        builder.notify(ID115Constants.UUID_CHARACTERISTIC_NOTIFY_NORMAL, true);
        // Health / bulk notify (0x0AF2) — required for v3 sync replies and legacy health transfer
        builder.notify(ID115Constants.UUID_CHARACTERISTIC_NOTIFY_HEALTH, true);

        // After CCCD writes: some peripherals reject MTU exchange if it runs first (GATT_INVALID_PDU).
        // Larger MTU lets 26-byte v3 HR (0x09) use a single write on 0x0AF1.
        builder.requestMtu(247);

        setTime(builder)
                .setWrist(builder)
                .setScreenOrientation(builder)
                .setGoal(builder);

        // Request battery info (GET 0x02 0x05); reply will arrive via onCharacteristicChanged
        builder.write(normalWriteCharacteristic, new byte[]{
                ID115Constants.CMD_ID_GET_INFO,
                ID115Constants.CMD_KEY_GET_BATT_INFO
        });
        // Request device info (GET 0x02 0x01) for firmware/version on the card
        builder.write(normalWriteCharacteristic, new byte[]{
                ID115Constants.CMD_ID_GET_INFO,
                ID115Constants.CMD_KEY_GET_DEVICE_INFO
        });
        // Request live data (GET 0x02 0xA0): Steps, MinutesRecordingLiveDataThisDay, Distance, GoalsHit, LastKnownHRM
        builder.write(normalWriteCharacteristic, new byte[]{
                ID115Constants.CMD_ID_GET_INFO,
                ID115Constants.CMD_KEY_GET_LIVE_DATA
        });

        builder.setDeviceState(GBDevice.State.INITIALIZED);
        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        // After RequestMtuAction completes, mMTU is updated; chunking must be computed then, not
        // when this TransactionBuilder is first assembled (getMTU() was still 23).
        builder.run(this::queueDeferredStoredSettingsSync);

        return builder;
    }

    private void queueDeferredStoredSettingsSync() {
        try {
            TransactionBuilder sync = performInitialized("toobur_apply_stored_settings");
            applyStoredTooburDeviceSettings(sync);
            queueGetWatchMtuInfo(sync);
            sync.queue();
        } catch (IOException e) {
            LOG.warn("TOOBUR: deferred stored settings sync failed", e);
        }
    }

    /**
     * GET 0x02 0xF0 — watch-reported MTU / PHY / DLE (IDOGetMtuInfo); shown under device card toggle details.
     */
    private void queueGetWatchMtuInfo(TransactionBuilder builder) {
        builder.write(normalWriteCharacteristic, new byte[]{
                ID115Constants.CMD_ID_GET_INFO,
                ID115Constants.CMD_KEY_GET_MTU_INFO
        });
    }

    private void queueGetWatchMtuInfoAlone() {
        try {
            TransactionBuilder tb = performInitialized("toobur_get_mtu_info");
            queueGetWatchMtuInfo(tb);
            tb.queue();
        } catch (IOException e) {
            LOG.warn("TOOBUR: GET MTU info (0x02 0xF0) failed", e);
        }
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          byte[] data) {
        UUID uuid = characteristic.getUuid();
        // 0x0AF2: v3 HR cmd 0x09 ack is a 0x33… frame (see packetdumps/logcat/app_fresh_launch.txt:184); log before super.
        if (ID115Constants.UUID_CHARACTERISTIC_NOTIFY_HEALTH.equals(uuid)) {
            TooburV3HrPackets.logV3Hr09RxIfPresent(data, LOG);
            return super.onCharacteristicChanged(gatt, characteristic, data);
        }
        if (!ID115Constants.UUID_CHARACTERISTIC_NOTIFY_NORMAL.equals(uuid)) {
            return super.onCharacteristicChanged(gatt, characteristic, data);
        }

        if (data == null || data.length < 2) {
            return super.onCharacteristicChanged(gatt, characteristic, data);
        }

        byte cmd = data[0];
        byte key = data[1];

        // Battery info reply (protocol_device_batt_info): head(2), type(1), voltage(2 LE mV), status(1), level(1)
        if (cmd == ID115Constants.CMD_ID_GET_INFO && key == ID115Constants.CMD_KEY_GET_BATT_INFO && data.length >= 7) {
            int voltageMv = (data[3] & 0xFF) | ((data[4] & 0xFF) << 8);
            int status = data[5] & 0xFF;
            int level = data[6] & 0xFF;

            batteryCmd.batteryIndex = 0;
            batteryCmd.level = level <= 100 ? level : GBDevice.BATTERY_UNKNOWN;
            batteryCmd.voltage = voltageMv > 0 ? voltageMv / 1000f : -1f;
            batteryCmd.state = statusToBatteryState(status);
            handleGBDeviceEvent(batteryCmd);
            LOG.debug("TOOBUR battery: {}%, {} mV, status {}", batteryCmd.level, voltageMv, status);
            return true;
        }

        // Device info reply (protocol_device_info): head(2), device_id(2), version(1), mode(1), ...
        if (cmd == ID115Constants.CMD_ID_GET_INFO && key == ID115Constants.CMD_KEY_GET_DEVICE_INFO && data.length >= 8) {
            int deviceId = (data[2] & 0xFF) | ((data[3] & 0xFF) << 8);
            int version = data[4] & 0xFF;
            versionCmd.fwVersion = String.format(Locale.US, "v%d", version);
            versionCmd.fwVersion2 = String.format(Locale.US, "ID 0x%04X", deviceId);
            handleGBDeviceEvent(versionCmd);
            LOG.debug("TOOBUR device info: id=0x{}, version={}", Integer.toHexString(deviceId), version);
            return true;
        }

        // GET 0x02 0xF0 — IDOGetMtuInfo (see htmlapp/confirmed-only.html): status, rx_mtu, tx_mtu, phy_speed, dle_length (LE)
        if (cmd == ID115Constants.CMD_ID_GET_INFO && key == ID115Constants.CMD_KEY_GET_MTU_INFO && data.length >= 11) {
            applyWatchMtuDeviceInfo(data);
            return true;
        }

        // GET 0x02 0xA0 live payload after 0x02 0xA0 header: Steps [u32 LE], MinutesRecordingLiveDataThisDay [u32 LE],
        // Distance [u32 LE], GoalsHit [u32 LE], LastKnownHRM [u8].
        if (cmd == ID115Constants.CMD_ID_GET_INFO && key == ID115Constants.CMD_KEY_GET_LIVE_DATA && data.length >= 19) {
            int steps = u32le(data, 2);
            int minutesRecordingLiveDataThisDay = u32le(data, 6);
            int distanceMeters = u32le(data, 10);
            int goalsHit = u32le(data, 14);
            int lastKnownHrm = data[18] & 0xFF;
            LOG.debug("TOOBUR live data: steps={}, min_recording_today={}, dist_m={}, goals_hit={}, last_hr_bpm={}",
                    steps, minutesRecordingLiveDataThisDay, distanceMeters, goalsHit, lastKnownHrm);
            TooburActivityDatabaseSync.persistLiveData(getContext(), getDevice(), steps, distanceMeters,
                    minutesRecordingLiveDataThisDay, lastKnownHrm);
            return true;
        }

        return super.onCharacteristicChanged(gatt, characteristic, data);
    }

    @Override
    public void onFetchRecordedData(int dataTypes) {
        onFetchRecordedData(dataTypes, false);
    }

    @Override
    public void onFetchRecordedData(int dataTypes, boolean autoFetch) {
        SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
        // GET 0x02 0xA0 (live steps/HR/…) — optional per auto vs manual (see list prefs).
        if (isLiveDataFetchEnabled(prefs, autoFetch)) {
            try {
                TransactionBuilder live = performInitialized("toobur_get_live_data");
                live.write(normalWriteCharacteristic, new byte[]{
                        ID115Constants.CMD_ID_GET_INFO,
                        ID115Constants.CMD_KEY_GET_LIVE_DATA
                });
                live.queue();
            } catch (IOException ex) {
                LOG.warn("TOOBUR: GET live data before v3 fetch failed", ex);
            }
        }
        // ID205/ID205U: firmware without v3 health sync -> use legacy ID115 fetch (CMD 0x08)
        if (String.valueOf(gbDevice.getName()).toUpperCase().startsWith("ID205")) {
            try {
                new nodomain.freeyourgadget.gadgetbridge.service.devices.id115.FetchActivityOperation(this, true).perform();
            } catch (IOException ex) {
                LOG.error("ID205: legacy ID115 fetch failed", ex);
            }
            return;
        }
        if (getEnabledV3HealthSyncDataTypes(prefs, autoFetch).length == 0) {
            if (!isLiveDataFetchEnabled(prefs, autoFetch)) {
                LOG.info("TOOBUR: fetch skipped (no live data and no v3 types) autoFetch={}", autoFetch);
            }
            return;
        }
        try {
            new TooburV3FetchHealthOperation(this, autoFetch).perform();
        } catch (IOException ex) {
            LOG.error("Unable to run v3 health fetch", ex);
        }
    }

    /**
     * Chunked ATT write for a full v3 frame on {@code 0x0AF6} — same route as {@code htmlapp/toobur-hr-csv.html}
     * (not {@code 0x0AF1}, which is for v3 bulk sync; HR mode must go on the normal channel).
     */
    private void queueChunkedV3HrWriteOnNormal(TransactionBuilder builder, byte[] fullFrame) {
        int payload = AbstractBTLEDeviceSupport.calcMaxWriteChunk(getMTU());
        List<byte[]> chunks = TooburV3BleChunkedWrite.splitForAttMtu(fullFrame, payload);
        if (chunks.size() > 1) {
            LOG.debug("TOOBUR v3 HR TX: mtu={} payloadPerWrite={} chunks={}", getMTU(), payload, chunks.size());
        }
        for (int i = 0; i < chunks.size(); i++) {
            builder.write(normalWriteCharacteristic, chunks.get(i));
            if (i + 1 < chunks.size()) {
                builder.wait(TooburV3BleChunkedWrite.CHUNK_GAP_MS);
            }
        }
    }

    /**
     * Apply continuous HR: one v3 cmd {@code 0x09} frame on {@code 0x0AF6} (unified packet; same as Gadgetbridge / HTML).
     */
    private void applyContinuousHrV3(TransactionBuilder builder) {
        var prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
        boolean continuousOn = isHrContinuousEnabled(prefs);
        int intervalSec = 300;
        try {
            intervalSec = Integer.parseInt(prefs.getString(PREF_TOOBUR_HR_INTERVAL_SECONDS, "300"));
        } catch (NumberFormatException ignored) {
        }
        intervalSec = TooburV3HrPackets.clampInterval(intervalSec);
        byte[] pkt = TooburV3HrPackets.buildHrUnified(continuousOn, intervalSec, nextV3Seq());
        queueChunkedV3HrWriteOnNormal(builder, pkt);
        LOG.info("TOOBUR HR: v3 0x09 TX on 0x0AF6 continuous={} interval={}s (ack on 0x0AF2)",
                continuousOn, intervalSec);
    }

    private void applySpo2SwitchFromPrefs(TransactionBuilder builder, SharedPreferences prefs) {
        boolean on = prefs.getBoolean(PREF_TOOBUR_SPO2_CONTINUOUS_ENABLED, true);
        builder.write(normalWriteCharacteristic, TooburHealthSwitchPackets.buildSpo2Switch(on));
        LOG.info("TOOBUR SpO2: SET 0x03 0x44 continuous={}", on);
    }

    private void applyPressureSwitchFromPrefs(TransactionBuilder builder, SharedPreferences prefs) {
        boolean on = prefs.getBoolean(PREF_TOOBUR_PRESSURE_CONTINUOUS_ENABLED, true);
        builder.write(normalWriteCharacteristic, TooburHealthSwitchPackets.buildPressureSwitch(on));
        LOG.info("TOOBUR stress: SET 0x03 0x45 continuous={}", on);
    }

    private void applyAutoActivitySwitchFromPrefs(TransactionBuilder builder, SharedPreferences prefs) {
        String preset = prefs.getString(PREF_TOOBUR_AUTO_ACTIVITY_PRESET, TooburAutoActivitySwitchPackets.PRESET_OFF);
        if (preset == null) {
            preset = TooburAutoActivitySwitchPackets.PRESET_OFF;
        }
        byte[] pkt = TooburAutoActivitySwitchPackets.build(preset);
        builder.write(normalWriteCharacteristic, pkt);
        LOG.info("TOOBUR auto activity: SET 0x03 0x49 preset={}", preset);
    }

    /**
     * Whether continuous HR is enabled — same logic for v3 TX and device card label (legacy list pref migration).
     */
    public static boolean isHrContinuousEnabled(SharedPreferences prefs) {
        if (!prefs.contains(PREF_TOOBUR_HR_CONTINUOUS_ENABLED)) {
            String legacy = prefs.getString(LEGACY_PREF_TOOBUR_HR_MODE, "auto");
            return "auto".equals(legacy);
        }
        return prefs.getBoolean(PREF_TOOBUR_HR_CONTINUOUS_ENABLED, true);
    }

    /**
     * Push TOOBUR-specific prefs to the band so hardware matches Gadgetbridge after connect.
     * Time, wrist, orientation, and goal are applied above in {@link ID115Support#initializeDevice(TransactionBuilder)}.
     */
    private void applyStoredTooburDeviceSettings(TransactionBuilder builder) {
        SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
        // HR v3 (0x09) first — some firmware processes SET order; avoid other 0x03 settings masking HR.
        applyContinuousHrV3(builder);
        applySpo2SwitchFromPrefs(builder, prefs);
        applyPressureSwitchFromPrefs(builder, prefs);
        applyAutoActivitySwitchFromPrefs(builder, prefs);
        applyMusicSwitchFromPrefs(builder, prefs);
        applyCallAlertFromPrefs(builder, prefs);
        applyDndFromPrefs(builder, prefs);
        applyRaiseToWakeFromPrefs(builder, prefs);
        applyWeatherSwitchFromPrefs(builder, prefs);
        LOG.info("TOOBUR: applied stored device settings after connect");
    }

    private void applyMusicSwitchFromPrefs(TransactionBuilder builder, SharedPreferences prefs) {
        builder.write(normalWriteCharacteristic, new byte[]{
                ID115Constants.CMD_ID_SETTINGS,
                ID115Constants.CMD_KEY_SET_MUSIC_SWITCH,
                prefs.getBoolean(PREF_TOOBUR_MUSIC_ENABLED, true) ? ID115Constants.CMD_ARG_MUSIC_ON : ID115Constants.CMD_ARG_MUSIC_OFF
        });
    }

    private void applyCallAlertFromPrefs(TransactionBuilder builder, SharedPreferences prefs) {
        boolean callOn = prefs.getBoolean(PREF_TOOBUR_CALL_ALERT_ENABLED, true);
        builder.write(normalWriteCharacteristic, new byte[]{
                ID115Constants.CMD_ID_SETTINGS,
                ID115Constants.CMD_KEY_SET_NOTICE,
                (byte) (callOn ? 1 : 0), 0, 0, (byte) (callOn ? 1 : 0), 3
        });
    }

    private void applyDndFromPrefs(TransactionBuilder builder, SharedPreferences prefs) {
        boolean dndOn = prefs.getBoolean(PREF_TOOBUR_DND_ENABLED, false);
        builder.write(normalWriteCharacteristic, new byte[]{
                ID115Constants.CMD_ID_SETTINGS,
                ID115Constants.CMD_KEY_SET_DO_NOT_DISTURB,
                (byte) (dndOn ? 1 : 0), 0, 0, 0, 0
        });
    }

    private void applyRaiseToWakeFromPrefs(TransactionBuilder builder, SharedPreferences prefs) {
        byte onOff = prefs.getBoolean(PREF_TOOBUR_RAISE_TO_WAKE, true)
                ? ID115Constants.CMD_ARG_GESTURE_ON
                : ID115Constants.CMD_ARG_GESTURE_OFF;
        builder.write(normalWriteCharacteristic, new byte[]{
                ID115Constants.CMD_ID_SETTINGS,
                ID115Constants.CMD_KEY_SET_UP_HAND_GESTURE,
                onOff,
                0x05, 0x01, 0x00, 0x00, 0x17, 0x3B
        });
    }

    private void applyWeatherSwitchFromPrefs(TransactionBuilder builder, SharedPreferences prefs) {
        byte weatherOn = prefs.getBoolean(PREF_TOOBUR_WEATHER_ENABLED, false) ? (byte) 1 : 0;
        builder.write(normalWriteCharacteristic, new byte[]{
                ID115Constants.CMD_ID_SETTINGS,
                ID115Constants.CMD_KEY_SET_WEATHER_SWITCH,
                weatherOn, 0, 0
        });
    }

    private void applyWatchMtuDeviceInfo(byte[] data) {
        int status = data[2] & 0xFF;
        int rxMtu = u16le(data, 3);
        int txMtu = u16le(data, 5);
        int phySpeed = u16le(data, 7);
        int dleLength = u16le(data, 9);
        String phyLabel = phySpeedToLabel(phySpeed);
        int phoneAttMtu = getMTU();
        android.content.Context ctx = GBApplication.getContext();
        String details = ctx.getString(R.string.toobur_device_info_mtu_details,
                rxMtu, txMtu, phyLabel, dleLength, status, phoneAttMtu);
        GBDevice dev = getDevice();
        dev.addDeviceInfo(new GenericItem(ctx.getString(R.string.toobur_device_info_mtu_title), details));
        dev.sendDeviceUpdateIntent(ctx);
        LOG.debug("TOOBUR MTU info: rx={} tx={} phy={} dle={} status={} phoneMtu={}",
                rxMtu, txMtu, phySpeed, dleLength, status, phoneAttMtu);
    }

    private static int u16le(byte[] d, int offset) {
        return (d[offset] & 0xFF) | ((d[offset + 1] & 0xFF) << 8);
    }

    private static int u32le(byte[] d, int offset) {
        return (d[offset] & 0xFF)
                | ((d[offset + 1] & 0xFF) << 8)
                | ((d[offset + 2] & 0xFF) << 16)
                | ((d[offset + 3] & 0xFF) << 24);
    }

    /** PHY speed raw value from GET 0xF0 — same labels as confirmed-only.html parser. */
    private static String phySpeedToLabel(int phySpeed) {
        if (phySpeed == 0) {
            return "invalid";
        }
        if (phySpeed == 1000) {
            return "1M";
        }
        if (phySpeed == 2000) {
            return "2M";
        }
        if (phySpeed == 512) {
            return "512K";
        }
        return String.valueOf(phySpeed);
    }

    private static BatteryState statusToBatteryState(int status) {
        switch (status) {
            case 1:
                return BatteryState.BATTERY_CHARGING;
            case 2:
                return BatteryState.BATTERY_CHARGING_FULL;
            case 3:
                return BatteryState.BATTERY_LOW;
            default:
                return BatteryState.BATTERY_NORMAL;
        }
    }

    /** Whether GET 0x02 0xA0 (live) should run for this fetch (manual vs auto). */
    public static boolean isLiveDataFetchEnabled(SharedPreferences prefs, boolean autoFetch) {
        String mode = v3LiveDataFetchMode(prefs);
        if (V3_SYNC_MODE_OFF.equals(mode)) {
            return false;
        }
        if (V3_SYNC_MODE_MANUAL.equals(mode)) {
            return !autoFetch;
        }
        return V3_SYNC_MODE_BOTH.equals(mode);
    }

    private static String v3LiveDataFetchMode(SharedPreferences prefs) {
        if (prefs.contains(PREF_TOOBUR_LIVE_DATA_FETCH_MODE)) {
            String m = prefs.getString(PREF_TOOBUR_LIVE_DATA_FETCH_MODE, V3_SYNC_MODE_BOTH);
            return m != null ? m : V3_SYNC_MODE_BOTH;
        }
        return V3_SYNC_MODE_BOTH;
    }

    /**
     * Which v3 health data types (0x04 sync) are enabled — used by {@link TooburV3FetchHealthOperation}.
     *
     * @param autoFetch {@code true} for background auto-fetch: exclude “manual only” types.
     *                  {@code false} for manual fetch: include “manual” and “auto and manual”.
     */
    public static int[] getEnabledV3HealthSyncDataTypes(SharedPreferences prefs, boolean autoFetch) {
        List<Integer> list = new ArrayList<>();
        for (int t : TooburV3HealthSync.V3_HEALTH_SYNC_DATA_TYPES) {
            if (isV3TypeEnabledForFetch(prefs, t, autoFetch)) {
                list.add(t);
            }
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    public static boolean isV3TypeEnabledForFetch(SharedPreferences prefs, int dataType, boolean autoFetch) {
        String mode = v3SyncMode(prefs, dataType);
        if (V3_SYNC_MODE_OFF.equals(mode)) {
            return false;
        }
        if (V3_SYNC_MODE_MANUAL.equals(mode)) {
            return !autoFetch;
        }
        return V3_SYNC_MODE_BOTH.equals(mode);
    }

    private static String v3SyncMode(SharedPreferences prefs, int dataType) {
        String modeKey = v3SyncModePrefKeyForType(dataType);
        if (prefs.contains(modeKey)) {
            String m = prefs.getString(modeKey, V3_SYNC_MODE_BOTH);
            return m != null ? m : V3_SYNC_MODE_BOTH;
        }
        String legacyKey = v3SyncPrefKeyForType(dataType);
        if (prefs.contains(legacyKey)) {
            return prefs.getBoolean(legacyKey, true) ? V3_SYNC_MODE_BOTH : V3_SYNC_MODE_OFF;
        }
        return V3_SYNC_MODE_BOTH;
    }

    static String v3SyncModePrefKeyForType(int dataType) {
        switch (dataType) {
            case 0x01:
                return PREF_TOOBUR_V3_SYNC_SPO2_MODE;
            case 0x02:
                return PREF_TOOBUR_V3_SYNC_PRESSURE_MODE;
            case 0x03:
                return PREF_TOOBUR_V3_SYNC_HR_DAY_MODE;
            case 0x04:
                return PREF_TOOBUR_V3_SYNC_ACTIVITY_MODE;
            case 0x06:
                return PREF_TOOBUR_V3_SYNC_SWIM_MODE;
            case 0x07:
                return PREF_TOOBUR_V3_SYNC_SLEEP_MODE;
            case 0x08:
                return PREF_TOOBUR_V3_SYNC_SPORT_MODE;
            default:
                return PREF_TOOBUR_V3_SYNC_SPO2_MODE;
        }
    }

    static String v3SyncPrefKeyForType(int dataType) {
        switch (dataType) {
            case 0x01:
                return PREF_TOOBUR_V3_SYNC_SPO2;
            case 0x02:
                return PREF_TOOBUR_V3_SYNC_PRESSURE;
            case 0x03:
                return PREF_TOOBUR_V3_SYNC_HR_DAY;
            case 0x04:
                return PREF_TOOBUR_V3_SYNC_ACTIVITY;
            case 0x06:
                return PREF_TOOBUR_V3_SYNC_SWIM;
            case 0x07:
                return PREF_TOOBUR_V3_SYNC_SLEEP;
            case 0x08:
                return PREF_TOOBUR_V3_SYNC_SPORT;
            default:
                return PREF_TOOBUR_V3_SYNC_SPO2;
        }
    }

    @Override
    public void onFindDevice(boolean start) {
        try {
            TransactionBuilder builder = performInitialized("find_device");
            builder.write(normalWriteCharacteristic, new byte[]{
                    ID115Constants.CMD_ID_APP_CONTROL,
                    ID115Constants.CMD_KEY_APP_FIND_DEVICE,
                    start ? ID115Constants.CMD_ARG_APP_START : ID115Constants.CMD_ARG_APP_STOP
            });
            builder.queue();
        } catch (IOException e) {
            LOG.warn("Unable to send find device", e);
        }
    }

    @Override
    public void onFindPhone(boolean start) {
        try {
            TransactionBuilder builder = performInitialized("find_phone");
            // SET 0x03 0x26: status (1=start), timeout_seconds (e.g. 30)
            builder.write(normalWriteCharacteristic, new byte[]{
                    ID115Constants.CMD_ID_SETTINGS,
                    ID115Constants.CMD_KEY_SET_FIND_PHONE,
                    (byte) (start ? 1 : 0),
                    (byte) 30
            });
            builder.queue();
        } catch (IOException e) {
            LOG.warn("Unable to send find phone", e);
        }
    }

    @Override
    public void onSetMusicState(MusicStateSpec stateSpec) {
        try {
            TransactionBuilder builder = performInitialized("music_control");
            // Control 0x06 0x01: 0x00 = start/play, 0x01 = stop/pause
            byte cmd = (stateSpec.state == MusicStateSpec.STATE_PLAYING)
                    ? ID115Constants.CMD_ARG_APP_START
                    : ID115Constants.CMD_ARG_APP_STOP;
            builder.write(normalWriteCharacteristic, new byte[]{
                    ID115Constants.CMD_ID_APP_CONTROL,
                    ID115Constants.CMD_KEY_APP_MUSIC,
                    cmd
            });
            builder.queue();
        } catch (IOException e) {
            LOG.warn("Unable to send music control", e);
        }
    }

    @Override
    public void onSetAlarms(ArrayList<? extends Alarm> alarms) {
        try {
            int slots = getDevice().getType().getDeviceCoordinator().getAlarmSlotCount(getDevice());
            TransactionBuilder builder = performInitialized("set_alarms");

            for (int slot = 0; slot < slots; slot++) {
                Alarm alarm = null;
                for (Alarm a : alarms) {
                    if (a.getPosition() == slot) {
                        alarm = a;
                        break;
                    }
                }
                byte status = (alarm != null && alarm.getEnabled()) ? ID115Constants.CMD_ARG_MUSIC_ON : (byte) 0x55;  // 0xAA on, 0x55 off
                int hour = alarm != null ? alarm.getHour() : 0;
                int minute = alarm != null ? alarm.getMinute() : 0;
                int repeat = alarm != null ? (alarm.getRepetition() & 0x7F) : 0;
                int snoozeMin = (alarm != null && alarm.getSnooze()) ? 5 : 0;
                // protocol_set_alarm: type 0=wake_up, 1=sleep, 2=sport, ...; we use 0
                builder.write(normalWriteCharacteristic, new byte[]{
                        ID115Constants.CMD_ID_SETTINGS,
                        ID115Constants.CMD_KEY_SET_ALARM,
                        (byte) slot,
                        status,
                        (byte) 0,
                        (byte) hour,
                        (byte) minute,
                        (byte) repeat,
                        (byte) snoozeMin
                });
            }
            builder.queue();
            LOG.info("Sent {} alarm slot(s) to device", slots);
        } catch (IOException e) {
            LOG.warn("Unable to send alarms", e);
        }
    }

    @Override
    public void onSendConfiguration(String config) {
        try {
            TransactionBuilder builder = performInitialized("config_" + config);
            var prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());

            switch (config) {
                case PREF_TOOBUR_ACTION_SEND_BIND:
                    builder.write(normalWriteCharacteristic, TOOBUR_BIND_START);
                    LOG.info("TOOBUR: manual bind start (04 01 F1…)");
                    break;
                case PREF_TOOBUR_ACTION_SEND_UNBIND:
                    builder.write(normalWriteCharacteristic, TOOBUR_UNBIND);
                    LOG.info("TOOBUR: manual unbind (04 02 F1…)");
                    break;
                case DeviceSettingsPreferenceConst.PREF_WEARLOCATION:
                    setWrist(builder);
                    break;
                case DeviceSettingsPreferenceConst.PREF_SCREEN_ORIENTATION:
                    setScreenOrientation(builder);
                    break;
                case PREF_TOOBUR_MUSIC_ENABLED:
                    applyMusicSwitchFromPrefs(builder, prefs);
                    break;
                case PREF_TOOBUR_CALL_ALERT_ENABLED:
                    applyCallAlertFromPrefs(builder, prefs);
                    break;
                case PREF_TOOBUR_DND_ENABLED:
                    applyDndFromPrefs(builder, prefs);
                    break;
                case PREF_TOOBUR_RAISE_TO_WAKE:
                    applyRaiseToWakeFromPrefs(builder, prefs);
                    break;
                case PREF_TOOBUR_HR_CONTINUOUS_ENABLED:
                case PREF_TOOBUR_HR_INTERVAL_SECONDS:
                    applyContinuousHrV3(builder);
                    break;
                case PREF_TOOBUR_SPO2_CONTINUOUS_ENABLED:
                    applySpo2SwitchFromPrefs(builder, prefs);
                    break;
                case PREF_TOOBUR_PRESSURE_CONTINUOUS_ENABLED:
                    applyPressureSwitchFromPrefs(builder, prefs);
                    break;
                case PREF_TOOBUR_AUTO_ACTIVITY_PRESET:
                    applyAutoActivitySwitchFromPrefs(builder, prefs);
                    break;
                case PREF_TOOBUR_WEATHER_ENABLED:
                    applyWeatherSwitchFromPrefs(builder, prefs);
                    break;
                default:
                    return;
            }
            builder.queue();
        } catch (IOException e) {
            LOG.warn("Unable to send configuration {}", config, e);
        }
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        super.onMtuChanged(gatt, mtu, status);
        if (status != BluetoothGatt.GATT_SUCCESS) {
            LOG.warn("TOOBUR: MTU exchange failed (status={}); v3 HR may use two 20-byte writes (MTU 23)", status);
        } else if (isInitialized()) {
            // Refresh watch GET 0x02 0xF0 line under toggle details; phone ATT in summary updates via getMTU().
            // Avoid performInitialized before INITIALIZED (would re-queue init).
            queueGetWatchMtuInfoAlone();
        }
    }

    @Override
    public void onGattConnectDelayScheduled(boolean pending) {
        oemDeferredGattConnectPending = pending;
    }

    /**
     * Used by DeviceCommunicationService to avoid re-queuing connect on every scan callback while
     * a deferred {@code connectGatt} is pending.
     */
    public boolean isOemDeferredGattConnectPending() {
        return oemDeferredGattConnectPending;
    }

    @Override
    public long getGattConnectDelayMs() {
        if (!GBApplication.getPrefs().getOemBleReconnectEnhancementsEnabled()) {
            return 0L;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            final long processAgeMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime();
            if (processAgeMs < 12_000L) {
                return Math.max(0L, 10_000L - processAgeMs);
            }
        }
        return 4_500L;
    }

    /** Next v3 packet sequence value (16-bit) without reserving it — pair with {@link #consumeV3SeqSlot()}. */
    public int peekV3Seq() {
        return (v3Seq + 1) & 0xFFFF;
    }

    /** Reserve the sequence value last returned by {@link #peekV3Seq()}. */
    public void consumeV3SeqSlot() {
        v3Seq = (v3Seq + 1) & 0xFFFF;
    }

    /** Allocate and return the next v3 sequence (16-bit). */
    public int nextV3Seq() {
        consumeV3SeqSlot();
        return v3Seq;
    }
}
