package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates the Bluetooth mesh.
 *
 * Each VirtualDevice represents a phone. The "gossip" step picks pairs of
 * devices that are nearby (we just say all devices are nearby for the demo)
 * and copies packets between them, decrementing TTL each hop.
 *
 * When a device with internet (a "bridge node") holds a packet, the demo's
 * /api/mesh/flush endpoint causes it to actually POST that packet to our
 * backend — simulating the moment a phone walks outside and gets 4G.
 */
@Service //This class contains business logic of - how packets moves
public class MeshSimulatorService { //This is the class that simulates how packets move from one virtual phone to another.

    private static final Logger log = LoggerFactory.getLogger(MeshSimulatorService.class); //Used for messages like: Packet abc123 injected at phone-alice, 15 packet transfers etc

    private final Map<String, VirtualDevice> devices = new ConcurrentHashMap<>(); //This stores all virtual phones

    public MeshSimulatorService() {
        // Default scenario: 4 offline phones in a basement, 1 phone outside with 4G
        seedDefaultDevices();
    }

    private void seedDefaultDevices() {
        devices.put("phone-alice",   new VirtualDevice("phone-alice",   false));
        devices.put("phone-stranger1", new VirtualDevice("phone-stranger1", false));
        devices.put("phone-stranger2", new VirtualDevice("phone-stranger2", false));
        devices.put("phone-stranger3", new VirtualDevice("phone-stranger3", false));
        devices.put("phone-bridge",  new VirtualDevice("phone-bridge",  true));
    }

    public Collection<VirtualDevice> getDevices() {
        return devices.values();
    } //retruns All Phones

    public VirtualDevice getDevice(String id) {
        return devices.get(id);
    } //returns single Phone

    /**
     * Sender drops a packet into the mesh by handing it to their own device.
     */
    public void inject(String senderDeviceId, MeshPacket packet) { //This is where a payment first enters the mesh.
        VirtualDevice sender = devices.get(senderDeviceId); //finds sender inside the map.
        if (sender == null) throw new IllegalArgumentException("Unknown device: " + senderDeviceId);
        sender.hold(packet); //Stores the packet inside senders phone
        log.info("Packet {} injected at {} (TTL={})",
                packet.getPacketId().substring(0, 8), senderDeviceId, packet.getTtl()); //Console shows something like Packet a3f9c821 injected at phone-alice (TTL=5)
    }         //Why substring(0,8)? A UUID is very long eg-550e8400-e29b-41d4-a716... Logs become difficult to read. Instead,the first 8 characters are enough.550e8400

    /**
     * One round of gossip. Every device shares everything it has with every
     * other device. TTL is decremented per hop; packets at TTL 0 stay where
     * they are but are not forwarded further.
     *
     * Real BLE gossip would be pair-by-pair when devices come into range.
     * For the demo we let everyone gossip with everyone in one round, which
     * is equivalent to "fast-forward N rounds of pairwise gossip".
     */
    public GossipResult gossipOnce() { //this is the heart of the mesh network, Everything happens here.
        int transfers = 0;
        List<VirtualDevice> deviceList = new ArrayList<>(devices.values()); //all devices

        // Snapshot what each device holds at the start of this round, so
        // we don't gossip the same packet through 5 devices in 1 step.
        Map<String, List<MeshPacket>> snapshot = new HashMap<>(); //meaning-Freeze the state of every phone at the beginning of this round. Newly received packets wait until the next gossip round.
        for (VirtualDevice d : deviceList) {
            snapshot.put(d.getDeviceId(), new ArrayList<>(d.getHeldPackets())); //take the snapshot of each phone
        }

        for (VirtualDevice src : deviceList) { //take each phone
            for (MeshPacket pkt : snapshot.get(src.getDeviceId())) { //Take every packet Alice had at the beginning of the round.
                if (pkt.getTtl() <= 0) continue; //if timetolive is less than or equal to 0 then skip it
                for (VirtualDevice dst : deviceList) { //Now consider every other phone.
                    if (dst == src) continue; //if considered phone is senders phone then skip (A phone shouldn't send a packet to itself.)
                    if (dst.holds(pkt.getPacketId())) continue; //Suppose any phone already has the packet that is sending, then Don't send it again.
                    MeshPacket copy = new MeshPacket(); //Why create a copy? Because every hop reduces TTL. If we changed the original object, all phones would suddenly see the lower TTL. Instead,every receiver gets its own copy. That's much closer to how a real network behaves.
                    copy.setPacketId(pkt.getPacketId());
                    copy.setTtl(pkt.getTtl() - 1); //eg-TTL = 5 receiver receives TTL = 4 Original packet still has TTL = 5
                    copy.setCreatedAt(pkt.getCreatedAt());
                    copy.setCiphertext(pkt.getCiphertext());
                    dst.hold(copy);
                    transfers++; //counts How many transfers happened this round? Useful for statistics.
                }
            }
        }

        log.info("Gossip round complete: {} packet transfers", transfers);
        return new GossipResult(transfers, snapshotMap());
    }

    public Map<String, Integer> snapshotMap() { //Returns something like phone-alice → 2 packets, phone-stranger1 → 4 packets, phone-bridge → 1 packet
        Map<String, Integer> m = new LinkedHashMap<>();
        for (VirtualDevice d : devices.values()) {
            m.put(d.getDeviceId(), d.packetCount());
        }
        return m;
    } //This is perfect for showing the mesh status on the dashboard.

    /**
     * Returns all packets held by devices with internet — these are what would
     * be uploaded to the backend the moment they reach connectivity.
     */
    public List<BridgeUpload> collectBridgeUploads() { //this method asks "Which phones currently have internet?"
        List<BridgeUpload> out = new ArrayList<>();
        for (VirtualDevice d : devices.values()) {
            if (!d.hasInternet()) continue; //Skip offline phones.
            for (MeshPacket pkt : d.getHeldPackets()) {
                out.add(new BridgeUpload(d.getDeviceId(), pkt)); //The result is a list of packets that are ready to be uploaded to the backend.
            }
        }
        return out;
    }

    public void resetMesh() {
        devices.values().forEach(VirtualDevice::clear);
    } //Delete all packets. Useful when starting a fresh demo

    public record GossipResult(int transfers, Map<String, Integer> deviceCounts) {} //Stores the result of one gossip round. transfers → Total number of packet transfers that happened. deviceCounts → Number of packets each device currently holds.
    public record BridgeUpload(String bridgeNodeId, MeshPacket packet) {} //Represents one packet that is ready to be uploaded by a bridge device. bridgeNodeId → ID of the bridge device (e.g., "phone-bridge"). packet → The MeshPacket that will be uploaded.
}
