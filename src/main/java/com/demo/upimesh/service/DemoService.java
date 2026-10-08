package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

/**
 * Helper service that:
 *   - seeds demo accounts on startup
 *   - simulates "sender phone creates an encrypted packet" flow (Create the encrypted payment packet)
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    @Autowired private AccountRepository accounts;
    @Autowired private HybridCryptoService crypto;
    @Autowired private ServerKeyHolder serverKey;

    @PostConstruct //it means Run this method after the Spring bean has been created and its dependencies have been injected.
    public void seedAccounts() { //it creates demo accounts if database is empty
        if (accounts.count() == 0) {  //prevents duplicate seeding every time the method runs.
            accounts.save(new Account("alice@demo", "Alice",   new BigDecimal("5000.00")));
            accounts.save(new Account("bob@demo",   "Bob",     new BigDecimal("1000.00")));
            accounts.save(new Account("carol@demo", "Carol",   new BigDecimal("2500.00")));
            accounts.save(new Account("dave@demo",  "Dave",    new BigDecimal("500.00")));
            log.info("Seeded 4 demo accounts");
        }
    }

    /**
     * Simulates the sender's phone:
     *   1. Build a PaymentInstruction with a fresh nonce + signedAt timestamp.
     *   2. Encrypt with the server's public key (hybrid RSA+AES).
     *   3. Wrap in a MeshPacket with TTL.
     *
     * In a real Android app, this exact code (minus the server-side reference)
     * would run on the phone. The phone would have already cached the server's
     * public key during a previous online session.
     */
    //It simulates what the sender's phone would do while offline.
    public MeshPacket createPacket(String senderVpa, String receiverVpa,
                                   BigDecimal amount, String pin, int ttl) throws Exception {
        PaymentInstruction instruction = new PaymentInstruction(  //These are the actual payment information before encryption.
                senderVpa,
                receiverVpa,
                amount,
                sha256Hex(pin), //eg-SHA-256(...)
                UUID.randomUUID().toString(), //8c71...       // nonce — guarantees uniqueness -This gives a unique value for each payment instruction.
                Instant.now().toEpochMilli()  //179136...     // signedAt (current time) — for freshness check
        );

        //PaymentInstruction → AES-256-GCM encryption → AES key protected with RSA-OAEP → Encrypted payload → sent through the mesh instead of exposing Alice → Bob → ₹500 in plaintext.
        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey()); //The payment instruction is encrypted using your hybrid crypto system.

        MeshPacket packet = new MeshPacket();              //Now we create the MeshPacket
        packet.setPacketId(UUID.randomUUID().toString());  //sets Unique identifier for the packet.(nonce)
        packet.setTtl(ttl);                                //sets ttl for the packet
        packet.setCreatedAt(Instant.now().toEpochMilli()); //This is the packet creation timestamp.
        packet.setCiphertext(ciphertext);                  //The encrypted payment becomes the packet's payload.
        return packet;
    }

    private String sha256Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes());
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
