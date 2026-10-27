/*  Copyright (C) 2025 idowatch / TOOBUR device support

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.toobur;

import android.bluetooth.le.ScanFilter;
import android.os.ParcelUuid;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.id115.ID115Constants;
import nodomain.freeyourgadget.gadgetbridge.devices.id115.ID115SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.ID115ActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.toobur.TooburSupport;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;

/**
 * Coordinator for TOOBUR-family devices (Toobur, Runlio, Biggerfive, Ksix, IDW, etc.)
 * that use the IDO/Realtek 0x0AF0 GATT protocol. Reuses ID115 protocol and support.
 * <p>
 * Some models are sold as <strong>TOOBUR BAND 8</strong> / <strong>A200</strong> and may reuse a
 * generic "Band 8" BLE name (similar wording to Xiaomi/Huawei products) — matching here avoids
 * mistaken identity when the device still advertises the VeryFit service.
 */
public class TooburCoordinator extends AbstractBLEDeviceCoordinator {

    private static final Pattern TOOBUR_NAME_PATTERN = Pattern.compile(
            "Toobur|Runlio|Biggerfive|Ksix|IDW|VeryFit|Ryze|ID205|A200|BAND\\s*8|BAND8",
            Pattern.CASE_INSENSITIVE
    );

    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        ParcelUuid service = new ParcelUuid(ID115Constants.UUID_SERVICE_ID115);
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(service).build();
        ScanFilter byName = new ScanFilter.Builder().setDeviceName("ID205U").build();
        return java.util.Arrays.asList(filter, byName);
    }

    @Override
    public boolean supports(GBDeviceCandidate candidate) {
        if (!candidate.supportsService(ID115Constants.UUID_SERVICE_ID115) && !String.valueOf(candidate.getName()).toUpperCase().startsWith("ID205")) {
            return false;
        }
        String name = candidate.getName();
        if (name == null || "(unknown)".equals(name)) {
            return false;
        }
        return TOOBUR_NAME_PATTERN.matcher(name).find();
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_NONE;
    }

    @Override
    public boolean supportsDataFetching(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsActivityTracking(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsFindDevice(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsMusicInfo(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsRealtimeData(@NonNull GBDevice device) {
        return false;
    }

    @Override
    public SampleProvider<? extends ActivitySample> getSampleProvider(GBDevice device, DaoSession session) {
        return new ID115SampleProvider(device, session);
    }

    @Override
    public boolean supportsSpo2(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsStressMeasurement(@NonNull GBDevice device) {
        return true;
    }

    @Nullable
    @Override
    public TimeSampleProvider<? extends Spo2Sample> getSpo2SampleProvider(GBDevice device, DaoSession session) {
        return new TooburSpo2SampleProvider(device, session);
    }

    @Nullable
    @Override
    public TimeSampleProvider<? extends StressSample> getStressSampleProvider(GBDevice device, DaoSession session) {
        return new TooburStressSampleProvider(device, session);
    }

    @Override
    public String getManufacturer() {
        return "IDO";
    }

    @Override
    public int[] getSupportedDeviceSpecificSettings(GBDevice device) {
        return new int[]{
                R.xml.devicesettings_wearlocation,
                R.xml.devicesettings_screenorientation,
                R.xml.devicesettings_toobur,
                R.xml.devicesettings_transliteration
        };
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return TooburSupport.class;
    }

    @Nullable
    @Override
    public DeviceSpecificSettingsCustomizer getDeviceSpecificSettingsCustomizer(GBDevice device) {
        return new TooburDeviceSpecificSettingsCustomizer();
    }

    @Override
    public int getAlarmSlotCount(GBDevice device) {
        return 5;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_toobur;
    }

    @Override
    public DeviceCoordinator.DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceCoordinator.DeviceKind.FITNESS_BAND;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_h30_h10;
    }

    @Override
    public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        Map<AbstractDao<?, ?>, Property> map = new HashMap<>(1);
        map.put(session.getID115ActivitySampleDao(), ID115ActivitySampleDao.Properties.DeviceId);
        return map;
    }

    @NonNull
    @Override
    public List<DeviceCardAction> getCustomActions() {
        return TooburDeviceCardActions.create();
    }
}
