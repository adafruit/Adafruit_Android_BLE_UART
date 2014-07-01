package com.adafruit.bleuart;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.Context;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;

public class BluetoothLeUart extends BluetoothGattCallback implements BluetoothAdapter.LeScanCallback {

    // UUIDs for UAT service and associated characteristics.
    public static UUID UART_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID TX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID RX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    // UUID for the BTLE client characteristic which is necessary for notifications.
    public static UUID CLIENT_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Internal UART state.
    private Context context;
    private WeakHashMap<Callback, Object> callbacks;
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx;
    private BluetoothGattCharacteristic rx;
    private boolean connectFirst;

    // Interface for a BluetoothLeUart client to be notified of UART actions.
    public interface Callback {
        public void onConnected(BluetoothLeUart uart);
        public void onConnectFailed(BluetoothLeUart uart);
        public void onDisconnected(BluetoothLeUart uart);
        public void onReceive(BluetoothLeUart uart, BluetoothGattCharacteristic rx);
        public void onDeviceFound(BluetoothDevice device);
    }

    public BluetoothLeUart(Context context) {
        super();
        this.context = context;
        this.callbacks = new WeakHashMap<Callback, Object>();
        this.adapter = BluetoothAdapter.getDefaultAdapter();
        this.gatt = null;
        this.tx = null;
        this.rx = null;
        this.connectFirst = false;
    }

    // Return instance of BluetoothGatt.
    public BluetoothGatt getGatt() {
        return gatt;
    }

    // Return true if connected to UART device, false otherwise.
    public boolean isConnected() {
        return (tx != null && rx != null);
    }

    // Send data to connected UART device.
    public void send(byte[] data) {
        if (tx == null || data == null || data.length == 0) {
            // Do nothing if there is no connection or message to send.
            return;
        }
        // Update TX characteristic value.  Note the setValue overload that takes a byte array must be used.
        tx.setValue(data);
        gatt.writeCharacteristic(tx);
    }

    // Send data to connected UART device.
    public void send(String data) {
        if (data != null && !data.isEmpty()) {
            send(data.getBytes(Charset.forName("UTF-8")));
        }
    }

    // Register the specified callback to receive UART callbacks.
    public void registerCallback(Callback callback) {
        callbacks.put(callback, null);
    }

    // Unregister the specified callback.
    public void unregisterCallback(Callback callback) {
        callbacks.remove(callback);
    }

    // Disconnect to a device if currently connected.
    public void disconnect() {
        if (gatt != null) {
            gatt.disconnect();
        }
        gatt = null;
        tx = null;
        rx = null;
    }

    // Stop any in progress UART device scan.
    public void stopScan() {
        if (adapter != null) {
            adapter.stopLeScan(this);
        }
    }

    // Start scanning for BLE UART devices.  Registered callback's onDeviceFound method will be called
    // when devices are found during scanning.
    public void startScan() {
        if (adapter != null) {
            adapter.startLeScan(this);
        }
    }

    // Connect to the first available UART device.
    public void connectFirstAvailable() {
        // Disconnect to any connected device.
        disconnect();
        // Stop any in progress device scan.
        stopScan();
        // Start scan and connect to first available device.
        connectFirst = true;
        startScan();
    }

    // Handlers for BluetoothGatt and LeScan events.
    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        if (newState == BluetoothGatt.STATE_CONNECTED) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Connected to device, start discovering services.
                if (!gatt.discoverServices()) {
                    // Error starting service discovery.
                    connectFailure();
                }
            }
            else {
                // Error connecting to device.
                connectFailure();
            }
        }
        else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
            // Disconnected, notify callbacks of disconnection.
            rx = null;
            tx = null;
            notifyOnDisconnected(this);
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);
        // Notify connection failure if service discovery failed.
        if (status == BluetoothGatt.GATT_FAILURE) {
            connectFailure();
            return;
        }
        // Save reference to each characteristic.
        tx = gatt.getService(UART_UUID).getCharacteristic(TX_UUID);
        rx = gatt.getService(UART_UUID).getCharacteristic(RX_UUID);
        // Setup notifications on RX characteristic changes (i.e. data received).
        // First call setCharacteristicNotification to enable notification.
        if (!gatt.setCharacteristicNotification(rx, true)) {
            // Stop if the characteristic notification setup failed.
            connectFailure();
            return;
        }
        // Next update the RX characteristic's client descriptor to enable notifications.
        BluetoothGattDescriptor desc = rx.getDescriptor(CLIENT_UUID);
        if (desc == null) {
            // Stop if the RX characteristic has no client descriptor.
            connectFailure();
            return;
        }
        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (!gatt.writeDescriptor(desc)) {
            // Stop if the client descriptor could not be written.
            connectFailure();
            return;
        }
        // Notify of connection completion.
        notifyOnConnected(this);
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);
        notifyOnReceive(this, characteristic);
    }

    @Override
    public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
        // Stop if the device doesn't have the UART service.
        if (!parseUUIDs(scanRecord).contains(UART_UUID)) {
            return;
        }
        // Notify registered callbacks of found device.
        notifyOnDeviceFound(device);
        // Connect to first found device if required.
        if (connectFirst) {
            // Stop scanning for devices.
            stopScan();
            // Prevent connections to future found devices.
            connectFirst = false;
            // Connect to device.
            gatt = device.connectGatt(context, true, this);
        }
    }

    // Private functions to simplify the notification of all callbacks of a certain event.
    private void notifyOnConnected(BluetoothLeUart uart) {
        for (Callback cb : callbacks.keySet()) {
            if (cb != null) {
                cb.onConnected(uart);
            }
        }
    }

    private void notifyOnConnectFailed(BluetoothLeUart uart) {
        for (Callback cb : callbacks.keySet()) {
            if (cb != null) {
                cb.onConnectFailed(uart);
            }
        }
    }

    private void notifyOnDisconnected(BluetoothLeUart uart) {
        for (Callback cb : callbacks.keySet()) {
            if (cb != null) {
                cb.onDisconnected(uart);
            }
        }
    }

    private void notifyOnReceive(BluetoothLeUart uart, BluetoothGattCharacteristic rx) {
        for (Callback cb : callbacks.keySet()) {
            if (cb != null ) {
                cb.onReceive(uart, rx);
            }
        }
    }

    private void notifyOnDeviceFound(BluetoothDevice device) {
        for (Callback cb : callbacks.keySet()) {
            if (cb != null) {
                cb.onDeviceFound(device);
            }
        }
    }

    // Notify callbacks of connection failure, and reset connection state.
    private void connectFailure() {
        rx = null;
        tx = null;
        notifyOnConnectFailed(this);
    }

    // Filtering by custom UUID is broken in Android 4.3 and 4.4, see:
    //   http://stackoverflow.com/questions/18019161/startlescan-with-128-bit-uuids-doesnt-work-on-native-android-ble-implementation?noredirect=1#comment27879874_18019161
    // This is a workaround function from the SO thread to manually parse advertisement data.
    private List<UUID> parseUUIDs(final byte[] advertisedData) {
        List<UUID> uuids = new ArrayList<UUID>();

        int offset = 0;
        while (offset < (advertisedData.length - 2)) {
            int len = advertisedData[offset++];
            if (len == 0)
                break;

            int type = advertisedData[offset++];
            switch (type) {
                case 0x02: // Partial list of 16-bit UUIDs
                case 0x03: // Complete list of 16-bit UUIDs
                    while (len > 1) {
                        int uuid16 = advertisedData[offset++];
                        uuid16 += (advertisedData[offset++] << 8);
                        len -= 2;
                        uuids.add(UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", uuid16)));
                    }
                    break;
                case 0x06:// Partial list of 128-bit UUIDs
                case 0x07:// Complete list of 128-bit UUIDs
                    // Loop through the advertised 128-bit UUID's.
                    while (len >= 16) {
                        try {
                            // Wrap the advertised bits and order them.
                            ByteBuffer buffer = ByteBuffer.wrap(advertisedData, offset++, 16).order(ByteOrder.LITTLE_ENDIAN);
                            long mostSignificantBit = buffer.getLong();
                            long leastSignificantBit = buffer.getLong();
                            uuids.add(new UUID(leastSignificantBit,
                                    mostSignificantBit));
                        } catch (IndexOutOfBoundsException e) {
                            // Defensive programming.
                            //Log.e(LOG_TAG, e.toString());
                            continue;
                        } finally {
                            // Move the offset to read the next uuid.
                            offset += 15;
                            len -= 16;
                        }
                    }
                    break;
                default:
                    offset += (len - 1);
                    break;
            }
        }
        return uuids;
    }
}
