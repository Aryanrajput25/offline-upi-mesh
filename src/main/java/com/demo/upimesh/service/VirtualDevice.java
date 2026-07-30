package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simulated phone in the mesh. Holds packets it has seen.
 *
 * In the real system, this state would be on a physical Android device,
 * with packets exchanged via BLE GATT characteristics.
 */
public class VirtualDevice { //Think of this class as one mobile phone. Your project doesn't use real Android phones or Bluetooth., it creates Java objects that behave like phones.

    private final String deviceId; //Every phone needs a unique identity.
    private final boolean hasInternet; //It simulates whether the phone currently has internet.

    //here key is packetId, Looking up a packet by ID in a map is much faster than searching through a list. Key=packet.getPacketId(), Value = packet
    private final Map<String, MeshPacket> heldPackets = new ConcurrentHashMap<>(); //A normal HashMap is not thread-safe. ConcurrentHashMap is designed for safe concurrent access.

    public VirtualDevice(String deviceId, boolean hasInternet) {
        this.deviceId = deviceId;
        this.hasInternet = hasInternet;
    }

    public String getDeviceId() { return deviceId; }
    public boolean hasInternet() { return hasInternet; }

    public void hold(MeshPacket packet) { //Store this packet in my memory.
        heldPackets.putIfAbsent(packet.getPacketId(), packet);
    } //putifabsent avoids unnecessary duplicate storage.

    public Collection<MeshPacket> getHeldPackets() {
        return heldPackets.values();
    } //This allows the mesh simulator to forward every stored packet.

    public boolean holds(String packetId) {
        return heldPackets.containsKey(packetId);
    } //Do I already have true of false

    public int packetCount() {
        return heldPackets.size();
    } //returns the no. of packets in each phone

    public void clear() {
        heldPackets.clear();
    } //Removes every packet.
}
