package com.demo.upimesh.controller;

import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Public REST surface.
 *
 * The endpoints split into three groups:
 *   /api/server-key      → so simulated senders can fetch the server's public key
 *   /api/mesh/*          → simulator endpoints (inject, gossip, flush)
 *   /api/bridge/ingest   → THE real production endpoint a real bridge node would hit
 *   /api/accounts, /api/transactions → for the dashboard
 */
//this handles the REST APIs
@RestController //tells Spring that this class handles HTTP REST requests and returns data directly as HTTP responses.
@RequestMapping("/api") //means every endpoint inside it starts with /api
public class ApiController {
    //Dependency injection
    @Autowired private ServerKeyHolder serverKey;
    @Autowired private DemoService demo;
    @Autowired private MeshSimulatorService mesh;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private AccountRepository accountRepo;
    @Autowired private TransactionRepository txRepo;
    @Autowired private IdempotencyService idempotency;

    //Instead of manually doing: DemoService demo = new DemoService(); → Spring creates the objects and injects them into the controller.

    // ------------------------------------------------------------------ key

    @GetMapping("/server-key")
    public Map<String, String> getServerPublicKey() { //This endpoint returns the server's public RSA key and crypto information.
        return Map.of(
                "publicKey", serverKey.getPublicKeyBase64(),
                "algorithm", "RSA-2048 / OAEP-SHA256",
                "hybridScheme", "RSA-OAEP encrypts an AES-256-GCM session key"
        );
    } //Why is this public key needed? ans-The sender needs the server's public key to encrypt the payment. The server's private key remains on the backend.

    // ---------------------------------------------------------------- demo

    /**
     * Demo helper: build a packet on the server (simulating a sender phone)
     * and inject it into the mesh at the given device.
     */
    //@RequestBody helps in - Take the incoming JSON request body and convert it into a Java object.
    @PostMapping("/demo/send") //When you click "Inject into Mesh” on the dashboard, JavaScript sends: senderVpa, receiverVpa, amount, pin, ttl, startDevice.
    public ResponseEntity<?> demoSend(@RequestBody DemoSendRequest req) throws Exception { //`POST /api/demo/send → Create encrypted packet → phone-alice holds packet

        MeshPacket packet = demo.createPacket( //it calls demoService's createPacket, Creating the packet. The controller calls: MeshPacket
                req.senderVpa, req.receiverVpa, req.amount, req.pin,
                req.ttl == null ? 5 : req.ttl); //So default TTL = 5, if ttl is not provided by the user

        String startDevice = req.startDevice == null ? "phone-alice" : req.startDevice;//Choosing the starting device, If no device is provided: phone-alice is used
        mesh.inject(startDevice, packet); //this calls MeshSimulatorService.inject(). this puts the packet into that simulated phone.

        //The backend sends JSON back to the browser.
        return ResponseEntity.ok(Map.of( //at last it returns- It sends information back to the frontend.
                "packetId", packet.getPacketId(),
                "ciphertextPreview", packet.getCiphertext().substring(0, 64) + "...", //it doesn't return the complete ciphertext — only the first 64 characters as a preview.
                "ttl", packet.getTtl(),
                "injectedAt", startDevice
        ));
    }

    public static class DemoSendRequest {
        public String senderVpa;
        public String receiverVpa;
        public BigDecimal amount;
        public String pin;
        public Integer ttl;
        public String startDevice;
    }

    // -------------------------------------------------------------- mesh sim

    @GetMapping("/mesh/state")
    public Map<String, Object> meshState() { //This endpoint tells the dashboard: "What is currently happening inside the simulated mesh?"
        List<Map<String, Object>> deviceData = new ArrayList<>();
        for (VirtualDevice d : mesh.getDevices()) { //it loops through every virtual device: and collects: deviceId,hasInternet,packetCount,packetIds.
            deviceData.add(Map.of(
                    "deviceId", d.getDeviceId(),
                    "hasInternet", d.hasInternet(),
                    "packetCount", d.packetCount(),
                    "packetIds", d.getHeldPackets().stream()
                            .map(p -> p.getPacketId().substring(0, 8))
                            .toList()
            ));
        }
        return Map.of(
                "devices", deviceData,
                "idempotencyCacheSize", idempotency.size() //it also return this So you can see how many packet hashes are currently remembered.
        );
    }

    @PostMapping("/mesh/gossip") //runs one simulated round of packet spreading.
    public Map<String, Object> meshGossip() {
        MeshSimulatorService.GossipResult r = mesh.gossipOnce(); //this calls MeshSimulatorService.gossipOnce(). This triggers one gossip round:
        return Map.of(
                "transfers", r.transfers(),
                "deviceCounts", r.deviceCounts()
        );
    }

    /**
     * "All bridge nodes simultaneously walk outside and get 4G."
     * They all upload everything they hold to /api/bridge/ingest.
     *
     * THIS is the moment the duplicate-storm idempotency case is tested:
     * if multiple bridge nodes hold the same packet, the server gets multiple
     * concurrent POSTs of the same ciphertext, and only one should settle.
     */
    //if packet had travelled to bridge device, after it the bridge device does flush()
    @PostMapping("/mesh/flush") //think: "All bridge devices have now reached an internet connection."
    public Map<String, Object> meshFlush() {
        List<MeshSimulatorService.BridgeUpload> uploads = mesh.collectBridgeUploads(); //This finds packets held by bridge devices.

        List<Map<String, Object>> results = new ArrayList<>();
        // Upload them in parallel to actually exercise concurrent idempotency.
        uploads.parallelStream().forEach(up -> {
            BridgeIngestionService.IngestResult r =
                    bridge.ingest(up.packet(), up.bridgeNodeId(), 5 - up.packet().getTtl()); //5 - up.packet().getTtl() calculates an hop count based on the initial TTL being 5
            synchronized (results) {
                results.add(Map.of(
                        "bridgeNode", up.bridgeNodeId(),
                        "packetId", up.packet().getPacketId().substring(0, 8),
                        "outcome", r.outcome(),
                        "reason", r.reason() == null ? "" : r.reason(),
                        "transactionId", r.transactionId() == null ? -1 : r.transactionId()
                ));
            }
        });

        return Map.of(
                "uploadsAttempted", uploads.size(),
                "results", results
        );
    }

    @PostMapping("/mesh/reset")
    public Map<String, Object> meshReset() {
        mesh.resetMesh();
        idempotency.clear();
        return Map.of("status", "mesh and idempotency cache cleared");
    }

    // -------------------------------------------------------------- bridge

    /**
     * THE PRODUCTION ENDPOINT.
     * In a real deployment, the Android app's bridge logic POSTs here whenever
     * the device has internet and is holding mesh packets.
     */
    @PostMapping("/bridge/ingest")
    public ResponseEntity<?> ingest(
            @RequestBody MeshPacket packet,
            @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown") String bridgeNodeId,
            @RequestHeader(value = "X-Hop-Count", defaultValue = "0") int hopCount) {

        BridgeIngestionService.IngestResult r = bridge.ingest(packet, bridgeNodeId, hopCount);
        return ResponseEntity.ok(r);
    }

    // ------------------------------------------------------------- accounts

    @GetMapping("/accounts")
    public List<Account> listAccounts() {
        return accountRepo.findAll();
    }

    @GetMapping("/transactions")
    public List<Transaction> listTransactions() {
        return txRepo.findTop20ByOrderByIdDesc();
    }
}
