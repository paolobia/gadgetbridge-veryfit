/*  Copyright (C) 2018-2024 Vadim Kaushan

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.id115;

import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.id115.ID115Constants;
import nodomain.freeyourgadget.gadgetbridge.devices.id115.ID115SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.ID115ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class FetchActivityOperation extends AbstractID115Operation {
    private static final Logger LOG = LoggerFactory.getLogger(FetchActivityOperation.class);
    private byte expectedCmd;
    private byte expectedSeq;
    private ArrayList<byte[]> packets;

    // Legacy keys: 0x03 = today, 0x05 = yesterday (observed on ID205U; the header carries the actual date)
    private static final byte CMD_KEY_FETCH_ACTIVITY_YESTERDAY = 0x05;
    private final byte[] keys;
    private int keyIndex;

    public FetchActivityOperation(ID115Support support) {
        this(support, false);
    }

    public FetchActivityOperation(ID115Support support, boolean includeYesterday) {
        super(support);
        keys = includeYesterday
                ? new byte[]{ID115Constants.CMD_KEY_FETCH_ACTIVITY_TODAY, CMD_KEY_FETCH_ACTIVITY_YESTERDAY}
                : new byte[]{ID115Constants.CMD_KEY_FETCH_ACTIVITY_TODAY};
    }

    @Override
    protected boolean isHealthOperation() {
        return true;
    }

    @Override
    protected void doPerform() throws IOException {
        keyIndex = 0;
        sendFetchRequest();
    }

    private void sendFetchRequest() throws IOException {
        byte key = keys[keyIndex];
        byte[] cmd = new byte[]{ID115Constants.CMD_ID_HEALTH_DATA, key, 0x01, 0x00, 0x00};

        expectedCmd = key;
        expectedSeq = 1;
        packets = new ArrayList<>();

        TransactionBuilder builder = performInitialized("send activity fetch request");
        builder.write(controlCharacteristic, cmd);
        builder.queue();
    }

    @Override
    protected void handleResponse(byte[] data) {
        if (!isOperationRunning()) {
            LOG.error("ignoring notification because operation is not running. Data length: {}", data.length);
            getSupport().logMessageContent(data);
            return;
        }

        if (data.length < 4 && !(data.length >= 2 && data[1] == (byte)0xEE)) {
            LOG.warn("short GATT response");
            return;
        }

        if (data[0] == ID115Constants.CMD_ID_HEALTH_DATA) {
            if (data[1] == (byte)0xEE) {
                LOG.info("Activity data transfer has finished.");
                parseAndStore();
                if (++keyIndex < keys.length) {
                    try {
                        sendFetchRequest();
                    } catch (IOException ex) {
                        LOG.error("Error requesting next activity day", ex);
                        operationFinished();
                    }
                } else {
                    operationFinished();
                }
            } else {
                if ((data[1] != expectedCmd) || (data[2] != expectedSeq)) {
                    GB.toast(getContext(), "Error fetching ID115 activity data, you may need to connect and disconnect", Toast.LENGTH_LONG, GB.ERROR);
                    operationFinished();
                    return;
                }
                expectedSeq += 1;

                byte[] payload = new byte[data.length - 4];
                System.arraycopy(data, 4, payload, 0, payload.length);
                packets.add(payload);
            }
        }
    }

    void parseAndStore() {
        if (packets.size() <= 1) {
            return;
        }

        byte[] header = packets.get(0);
        int year = ((header[1] & 0xFF) << 8) | (header[0] & 0xFF);
        int month = header[2] & 0xFF;
        int day = header[3] & 0xFF;
        int sampleDurationMinutes = header[6] & 0xFF;
        Calendar calendar = new GregorianCalendar(year, month - 1, day);
        int ts = (int)(calendar.getTimeInMillis() / 1000);
        int dt = sampleDurationMinutes * 60;

        ArrayList<ID115ActivitySample> samples = new ArrayList<>();

        // Samples are 5 bytes each but packet payloads are 16 bytes: the samples form one
        // continuous stream across packets, so concatenate before splitting.
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        for (int i = 2; i < packets.size(); i++) {
            byte[] packet = packets.get(i);
            stream.write(packet, 0, packet.length);
        }
        byte[] all = stream.toByteArray();
        for (int j = 0; j <= all.length - 5; j += 5) {
            byte[] sampleData = Arrays.copyOfRange(all, j, j + 5);

            ID115ActivitySample sample = parseSample(sampleData);
            if (sample == null && j == 0) {
                // always store the first slot so a stale per-day summary row at midnight is replaced
                sample = new ID115ActivitySample();
                sample.setSteps(0);
                sample.setHeartRate(ActivitySample.NOT_MEASURED);
            }
            if (sample != null) {
                sample.setTimestamp(ts);
                sample.setRawKind(ActivityKind.ACTIVITY.getCode());
                samples.add(sample);
            }
            ts += dt;
        }

        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            ID115ActivitySample[] sampleArray = samples.toArray(new ID115ActivitySample[0]);
            long userId = DBHelper.getUser(dbHandler.getDaoSession()).getId();
            long deviceId = DBHelper.getDevice(getDevice(), dbHandler.getDaoSession()).getId();
            for (ID115ActivitySample sample: sampleArray) {
                sample.setUserId(userId);
                sample.setDeviceId(deviceId);
            }

            ID115SampleProvider provider = new ID115SampleProvider(getDevice(), dbHandler.getDaoSession());
            provider.addGBActivitySamples(Arrays.asList(sampleArray));
        } catch (Exception ex) {
            GB.toast(getContext(), "Error saving activity data: " + ex.getLocalizedMessage(), Toast.LENGTH_LONG, GB.ERROR, ex);
        }
    }

    ID115ActivitySample parseSample(byte[] data) {
        int d01 = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
        int d12 = ((data[2] & 0xFF) << 8) | (data[1] & 0xFF);
        int d23 = ((data[3] & 0xFF) << 8) | (data[2] & 0xFF);
        int d34 = ((data[4] & 0xFF) << 8) | (data[3] & 0xFF);
        int stepCount = (d01 >> 2) & 0xFFF;
        int activeTime = (d12 >> 6) & 0xF;
        int calories = (d23 >> 2) & 0x3FF;
        int distance = (d34 >> 4);

        if (stepCount == 0) {
            return null;
        }

        ID115ActivitySample sample = new ID115ActivitySample();
        sample.setSteps(stepCount);
        sample.setActiveTimeMinutes(activeTime);
        sample.setCaloriesBurnt(calories);
        sample.setDistanceMeters(distance);
        sample.setHeartRate(ActivitySample.NOT_MEASURED);
        return sample;
    }
}
