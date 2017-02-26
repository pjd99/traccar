/*
 * Copyright 2013 - 2017 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.Context;
import org.traccar.DeviceSession;
import org.traccar.helper.Checksum;
import org.traccar.helper.Log;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;

public class AplicomProtocolDecoder extends BaseProtocolDecoder {

    public AplicomProtocolDecoder(AplicomProtocol protocol) {
        super(protocol);
    }

    private static final long IMEI_BASE_TC65_V20 = 0x1437207000000L;
    private static final long IMEI_BASE_TC65_V28 = 358244010000000L;
    private static final long IMEI_BASE_TC65I_V11 = 0x14143B4000000L;

    private static boolean validateImei(long imei) {
        return Checksum.luhn(imei / 10) == imei % 10;
    }

    private static long imeiFromUnitId(long unitId) {

        if (unitId == 0) {

            return 0;

        } else {

            // Try TC65i
            long imei = IMEI_BASE_TC65I_V11 + unitId;
            if (validateImei(imei)) {
                return imei;
            }

            // Try TC65 v2.8
            imei = IMEI_BASE_TC65_V28 + ((unitId + 0xA8180) & 0xFFFFFF);
            if (validateImei(imei)) {
                return imei;
            }

            // Try TC65 v2.0
            imei = IMEI_BASE_TC65_V20 + unitId;
            if (validateImei(imei)) {
                return imei;
            }

        }

        return unitId;
    }

    private static final int DEFAULT_SELECTOR_D = 0x0002fC;
    private static final int DEFAULT_SELECTOR_E = 0x007ffc;
    private static final int DEFAULT_SELECTOR_F = 0x0007fd;

    private static final int EVENT_DATA = 119;

    private void decodeEventData(Position position, ChannelBuffer buf, int event) {
        switch (event) {
            case 2:
            case 40:
                buf.readUnsignedByte();
                break;
            case 9:
                buf.readUnsignedMedium();
                break;
            case 31:
            case 32:
                buf.readUnsignedShort();
                break;
            case 38:
                buf.skipBytes(4 * 9);
                break;
            case 113:
                buf.readUnsignedInt();
                buf.readUnsignedByte();
                break;
            case 121:
            case 142:
                buf.readLong();
                break;
            case 130:
                buf.readUnsignedInt(); // incorrect
                break;
            case 188:
                decodeEB(position, buf);
                break;
            default:
                break;
        }
    }

    private void decodeCanData(ChannelBuffer buf, Position position) {

        buf.readUnsignedMedium(); // packet identifier
        position.set(Position.KEY_VERSION_FW, buf.readUnsignedByte()); // version
        int count = buf.readUnsignedByte();
        buf.readUnsignedByte(); // batch count
        buf.readUnsignedShort(); // selector bit
        buf.readUnsignedInt(); // timestamp

        buf.skipBytes(8);

        ArrayList<ChannelBuffer> values = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            values.add(buf.readBytes(8));
        }

        for (int i = 0; i < count; i++) {
            ChannelBuffer value = values.get(i);
            switch (buf.readInt()) {
                case 0x20D:
                    position.set(Position.KEY_RPM, ChannelBuffers.swapShort(value.readShort()));
                    position.set("dieselTemperature", ChannelBuffers.swapShort(value.readShort()) * 0.1);
                    position.set("batteryVoltage", ChannelBuffers.swapShort(value.readShort()) * 0.01);
                    position.set("supplyAirTempDep1", ChannelBuffers.swapShort(value.readShort()) * 0.1);
                    break;
                case 0x30D:
                    position.set("activeAlarm", ChannelBuffers.hexDump(value));
                    break;
                case 0x40C:
                    position.set("airTempDep1", ChannelBuffers.swapShort(value.readShort()) * 0.1);
                    position.set("airTempDep2", ChannelBuffers.swapShort(value.readShort()) * 0.1);
                    break;
                case 0x40D:
                    position.set("coldUnitState", ChannelBuffers.hexDump(value));
                    break;
                case 0x50C:
                    position.set("defrostTempDep1", ChannelBuffers.swapShort(value.readShort()) * 0.1);
                    position.set("defrostTempDep2", ChannelBuffers.swapShort(value.readShort()) * 0.1);
                    break;
                case 0x50D:
                    position.set("condenserPressure", ChannelBuffers.swapShort(value.readShort()) * 0.1);
                    position.set("suctionPressure", ChannelBuffers.swapShort(value.readShort()) * 0.1);
                    break;
                case 0x58C:
                    value.readByte();
                    value.readShort(); // index
                    switch (value.readByte()) {
                        case 0x01:
                            position.set("setpointZone1", ChannelBuffers.swapInt(value.readInt()) * 0.1);
                            break;
                        case 0x02:
                            position.set("setpointZone2", ChannelBuffers.swapInt(value.readInt()) * 0.1);
                            break;
                        case 0x05:
                            position.set("unitType", ChannelBuffers.swapInt(value.readInt()));
                            break;
                        case 0x13:
                            position.set("dieselHours", ChannelBuffers.swapInt(value.readInt()) / 60 / 60);
                            break;
                        case 0x14:
                            position.set("electricHours", ChannelBuffers.swapInt(value.readInt()) / 60 / 60);
                            break;
                        case 0x17:
                            position.set("serviceIndicator", ChannelBuffers.swapInt(value.readInt()));
                            break;
                        case 0x18:
                            position.set("softwareVersion", ChannelBuffers.swapInt(value.readInt()) * 0.01);
                            break;
                        default:
                            break;
                    }
                    break;
                default:
                    Log.warning(new UnsupportedOperationException());
                    break;
            }
        }
    }

    private void decodeD(Position position, ChannelBuffer buf, int selector, int event) {

        if ((selector & 0x0008) != 0) {
            position.setValid((buf.readUnsignedByte() & 0x40) != 0);
        } else {
            getLastLocation(position, null);
        }

        if ((selector & 0x0004) != 0) {
            buf.skipBytes(4); // snapshot time
        }

        if ((selector & 0x0008) != 0) {
            position.setTime(new Date(buf.readUnsignedInt() * 1000));
            position.setLatitude(buf.readInt() / 1000000.0);
            position.setLongitude(buf.readInt() / 1000000.0);
            position.set(Position.KEY_SATELLITES_VISIBLE, buf.readUnsignedByte());
        }

        if ((selector & 0x0010) != 0) {
            position.setSpeed(UnitsConverter.knotsFromKph(buf.readUnsignedByte()));
            position.set("maximumSpeed", buf.readUnsignedByte()); // maximum speed
            position.setCourse(buf.readUnsignedByte() * 2.0);
        }

        if ((selector & 0x0040) != 0) {
            position.set(Position.KEY_INPUT, buf.readUnsignedByte());
        }

        if ((selector & 0x0020) != 0) {
            position.set(Position.PREFIX_ADC + 1, buf.readUnsignedShort());
            position.set(Position.PREFIX_ADC + 2, buf.readUnsignedShort());
            position.set(Position.PREFIX_ADC + 3, buf.readUnsignedShort());
            position.set(Position.PREFIX_ADC + 4, buf.readUnsignedShort());
        }

        if ((selector & 0x8000) != 0) {
            position.set(Position.KEY_POWER, buf.readUnsignedShort() / 1000.0);
            position.set(Position.KEY_BATTERY, buf.readUnsignedShort());
        }

        // Pulse rate 1
        if ((selector & 0x10000) != 0) {
            buf.readUnsignedShort();
            buf.readUnsignedInt();
        }

        // Pulse rate 2
        if ((selector & 0x20000) != 0) {
            buf.readUnsignedShort();
            buf.readUnsignedInt();
        }

        if ((selector & 0x0080) != 0) {
            position.set("trip1", buf.readUnsignedInt());
        }

        if ((selector & 0x0100) != 0) {
            position.set("trip2", buf.readUnsignedInt());
        }

        if ((selector & 0x0040) != 0) {
            position.set(Position.KEY_OUTPUT, buf.readUnsignedByte());
        }

        if ((selector & 0x0200) != 0) {
            position.set(Position.KEY_RFID, (((long) buf.readUnsignedShort()) << 32) + buf.readUnsignedInt());
        }

        if ((selector & 0x0400) != 0) {
            buf.readUnsignedByte(); // Keypad
        }

        if ((selector & 0x0800) != 0) {
            position.setAltitude(buf.readShort());
        }

        if ((selector & 0x2000) != 0) {
            buf.readUnsignedShort(); // snapshot counter
        }

        if ((selector & 0x4000) != 0) {
            buf.skipBytes(8); // state flags
        }

        if ((selector & 0x80000) != 0) {
            buf.skipBytes(11); // cell info
        }

        if ((selector & 0x1000) != 0) {
            decodeEventData(position, buf, event);
        }

        if (Context.getConfig().getBoolean(getProtocolName() + ".can")
                && buf.readable() && (selector & 0x1000) != 0 && event == EVENT_DATA) {
            decodeCanData(buf, position);
        }
    }

    private void decodeE(Position position, ChannelBuffer buf, int selector) {

        if ((selector & 0x0008) != 0) {
            position.set("tachographEvent", buf.readUnsignedShort());
        }

        if ((selector & 0x0004) != 0) {
            getLastLocation(position, new Date(buf.readUnsignedInt() * 1000));
        } else {
            getLastLocation(position, null);
        }

        if ((selector & 0x0010) != 0) {
            String time = buf.readUnsignedByte() + "s " + buf.readUnsignedByte() + "m " + buf.readUnsignedByte() + "h "
                    + buf.readUnsignedByte() + "M " + buf.readUnsignedByte() + "D " + buf.readUnsignedByte() + "Y "
                    + buf.readByte() + "m " + buf.readByte() + "h";
            position.set("tachographTime", time);
        }

        position.set("workState", buf.readUnsignedByte());
        position.set("driver1State", buf.readUnsignedByte());
        position.set("driver2State", buf.readUnsignedByte());

        if ((selector & 0x0020) != 0) {
            position.set("tachographStatus", buf.readUnsignedByte());
        }

        if ((selector & 0x0040) != 0) {
            position.set(Position.KEY_OBD_SPEED, buf.readUnsignedShort() / 256.0);
        }

        if ((selector & 0x0080) != 0) {
            position.set(Position.KEY_OBD_ODOMETER, buf.readUnsignedInt() * 5);
        }

        if ((selector & 0x0100) != 0) {
            position.set(Position.KEY_ODOMETER_TRIP, buf.readUnsignedInt() * 5);
        }

        if ((selector & 0x8000) != 0) {
            position.set("kFactor", buf.readUnsignedShort() * 0.001 + " pulses/m");
        }

        if ((selector & 0x0200) != 0) {
            position.set(Position.KEY_RPM, buf.readUnsignedShort() * 0.125);
        }

        if ((selector & 0x0400) != 0) {
            position.set("extraInfo", buf.readUnsignedShort());
        }

        if ((selector & 0x0800) != 0) {
            position.set(Position.KEY_VIN, buf.readBytes(18).toString(StandardCharsets.US_ASCII).trim());
        }
    }

    private void decodeH(Position position, ChannelBuffer buf, int selector) {

        if ((selector & 0x0004) != 0) {
            getLastLocation(position, new Date(buf.readUnsignedInt() * 1000));
        } else {
            getLastLocation(position, null);
        }

        if ((selector & 0x0040) != 0) {
            buf.readUnsignedInt(); // reset time
        }

        if ((selector & 0x2000) != 0) {
            buf.readUnsignedShort(); // snapshot counter
        }

        int index = 1;
        while (buf.readableBytes() > 0) {

            position.set("h" + index + "Index", buf.readUnsignedByte());

            buf.readUnsignedShort(); // length

            int n = buf.readUnsignedByte();
            int m = buf.readUnsignedByte();

            position.set("h" + index + "XLength", n);
            position.set("h" + index + "YLength", m);

            if ((selector & 0x0008) != 0) {
                position.set("h" + index + "XType", buf.readUnsignedByte());
                position.set("h" + index + "YType", buf.readUnsignedByte());
                position.set("h" + index + "Parameters", buf.readUnsignedByte());
            }

            boolean percentageFormat = (selector & 0x0020) != 0;

            StringBuilder data = new StringBuilder();
            for (int i = 0; i < n * m; i++) {
                if (percentageFormat) {
                    data.append(buf.readUnsignedByte() * 0.5).append("%").append(" ");
                } else {
                    data.append(buf.readUnsignedShort()).append(" ");
                }
            }

            position.set("h" + index + "Data", data.toString().trim());

            position.set("h" + index + "Total", buf.readUnsignedInt());

            if ((selector & 0x0010) != 0) {
                int k = buf.readUnsignedByte();

                data = new StringBuilder();
                for (int i = 1; i < n; i++) {
                    if (k == 1) {
                        data.append(buf.readByte()).append(" ");
                    } else if (k == 2) {
                        data.append(buf.readShort()).append(" ");
                    }
                }
                position.set("h" + index + "XLimits", data.toString().trim());

                data = new StringBuilder();
                for (int i = 1; i < m; i++) {
                    if (k == 1) {
                        data.append(buf.readByte()).append(" ");
                    } else if (k == 2) {
                        data.append(buf.readShort()).append(" ");
                    }
                }
                position.set("h" + index + "YLimits", data.toString().trim());
            }

            index += 1;
        }
    }

    private void decodeEB(Position position, ChannelBuffer buf) {

        if (buf.readByte() != (byte) 'E' || buf.readByte() != (byte) 'B') {
            return;
        }

        position.set(Position.KEY_VERSION_FW, buf.readUnsignedByte()); // version
        buf.readUnsignedShort(); // event
        buf.readUnsignedByte(); // data validity
        buf.readUnsignedByte(); // towed
        buf.readUnsignedShort(); // length

        while (buf.readableBytes() > 0) {
            buf.readUnsignedByte(); // towed position
            int type = buf.readUnsignedByte();
            int length = buf.readUnsignedByte();

            if (type == 0x01) {
                position.set("brakeFlags", ChannelBuffers.hexDump(buf.readBytes(length)));
            } else if (type == 0x02) {
                position.set("wheelSpeed", buf.readUnsignedShort() / 256.0);
                position.set("wheelSpeedDifference", buf.readUnsignedShort() / 256.0 - 125.0);
                position.set("lateralAcceleration", buf.readUnsignedByte() / 10.0 - 12.5);
                position.set("vehicleSpeed", buf.readUnsignedShort() / 256.0);
            } else if (type == 0x03) {
                position.set("axleLoadSum", buf.readUnsignedShort() * 2);
            } else if (type == 0x04) {
                position.set("tyrePressure", buf.readUnsignedByte() * 10);
                position.set("pneumaticPressure", buf.readUnsignedByte() * 5);
            } else if (type == 0x05) {
                position.set("brakeLining", buf.readUnsignedByte() * 0.4);
                position.set("brakeTemperature", buf.readUnsignedByte() * 10);
            } else if (type == 0x06) {
                position.set(Position.KEY_ODOMETER, buf.readUnsignedInt() * 5);
                position.set(Position.KEY_ODOMETER_TRIP, buf.readUnsignedInt() * 5);
                position.set(Position.KEY_ODOMETER_SERVICE, (buf.readUnsignedInt() - 2105540607) * 5);
            } else if (type == 0x0A) {
                ChannelBuffer brakeData = buf.readBytes(length);
                position.set("absStatusCounter", brakeData.readUnsignedShort());
                position.set("atvbStatusCounter", brakeData.readUnsignedShort());
                position.set("vdcActiveCounter", brakeData.readUnsignedShort());
            } else if (type == 0x0B) {
                position.set("brakeMinMaxData", ChannelBuffers.hexDump(buf.readBytes(length)));
            } else if (type == 0x0C) {
                position.set("missingPgn", ChannelBuffers.hexDump(buf.readBytes(length)));
            } else if (type == 0x0D) {
                switch (buf.readUnsignedByte()) {
                    case 1:
                        position.set("brakeManufacturer", "Wabco");
                        break;
                    case 2:
                        position.set("brakeManufacturer", "Knorr");
                        break;
                    case 3:
                        position.set("brakeManufacturer", "Haldex");
                        break;
                    default:
                        position.set("brakeManufacturer", "Unknown");
                        break;
                }
                buf.readUnsignedByte();
                position.set(Position.KEY_VIN, buf.readBytes(17).toString(StandardCharsets.US_ASCII));
                position.set("towedDetectionStatus", buf.readUnsignedByte());
            } else if (type == 0x0E) {
                buf.skipBytes(length);
            }
        }
    }

    private void decodeF(Position position, ChannelBuffer buf, int selector) {

        getLastLocation(position, null);

        buf.readUnsignedShort(); // event

        if ((selector & 0x0004) != 0) {
            buf.skipBytes(4); // snapshot time
        }

        buf.readUnsignedByte(); // data validity

        if ((selector & 0x0008) != 0) {
            position.set(Position.KEY_RPM, buf.readUnsignedShort());
            position.set("rpmMax", buf.readUnsignedShort());
            position.set("rpmMin", buf.readUnsignedShort());
        }

        if ((selector & 0x0010) != 0) {
            position.set("engineTemp", buf.readShort());
            position.set("engineTempMax", buf.readShort());
            position.set("engineTempMin", buf.readShort());
        }

        if ((selector & 0x0020) != 0) {
            position.set(Position.KEY_HOURS, buf.readUnsignedInt());
            position.set("serviceDistance", buf.readInt());
            buf.readUnsignedByte(); // driver activity
            position.set(Position.KEY_THROTTLE, buf.readUnsignedByte());
            position.set(Position.KEY_FUEL_LEVEL, buf.readUnsignedByte());
        }

        if ((selector & 0x0040) != 0) {
            position.set("totalFuelUsed", buf.readUnsignedInt());
        }

        if ((selector & 0x0080) != 0) {
            position.set(Position.KEY_ODOMETER, buf.readUnsignedInt());
        }

        if ((selector & 0x0100) != 0) {
            position.set(Position.KEY_OBD_SPEED, buf.readUnsignedByte());
            position.set("speedMax", buf.readUnsignedByte());
            position.set("speedMin", buf.readUnsignedByte());
            position.set("hardBreaking", buf.readUnsignedByte());
        }

        if ((selector & 0x0200) != 0) {
            buf.readUnsignedByte(); // tachograph based speed
            buf.readUnsignedByte(); // driver 1 state
            buf.readUnsignedByte(); // driver 2 state
            buf.readUnsignedByte(); // tachograph status
            position.set("overspeedCount", buf.readUnsignedByte());
        }

    }

    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ChannelBuffer buf = (ChannelBuffer) msg;

        char protocol = (char) buf.readByte();
        int version = buf.readUnsignedByte();

        String imei;
        if ((version & 0x80) != 0) {
            imei = String.valueOf((buf.readUnsignedInt() << (3 * 8)) | buf.readUnsignedMedium());
        } else {
            imei = String.valueOf(imeiFromUnitId(buf.readUnsignedMedium()));
        }

        buf.readUnsignedShort(); // length

        int selector = DEFAULT_SELECTOR_D;
        if (protocol == 'E') {
            selector = DEFAULT_SELECTOR_E;
        } else if (protocol == 'F') {
            selector = DEFAULT_SELECTOR_F;
        }
        if ((version & 0x40) != 0) {
            selector = buf.readUnsignedMedium();
        }

        Position position = new Position();
        position.setProtocol(getProtocolName());
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);
        if (deviceSession == null) {
            return null;
        }
        position.setDeviceId(deviceSession.getDeviceId());

        int event = buf.readUnsignedByte();
        position.set(Position.KEY_EVENT, event);
        position.set("eventInfo", buf.readUnsignedByte());

        if (protocol == 'D') {
            decodeD(position, buf, selector, event);
        } else if (protocol == 'E') {
            decodeE(position, buf, selector);
        } else if (protocol == 'H') {
            decodeH(position, buf, selector);
        } else if (protocol == 'F') {
            decodeF(position, buf, selector);
        } else {
            return null;
        }

        return position;
    }

}
