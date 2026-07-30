package com.demo.upimesh.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

/**
 * The over-the-wire format. This is what hops from phone to phone via Bluetooth.
 *
 * The intermediate phones can read the OUTER fields (packetId, ttl, createdAt)
 * because they need them for routing and dedup. They CANNOT read `ciphertext` —
 * that's encrypted with the server's public key.
 *
 * NOTE on outer-field tampering:
 *   A malicious intermediate could change `packetId` or `createdAt`. That's why
 *   we use the ciphertext's hash (not packetId) as the idempotency key on the
 *   server. The ciphertext is authenticated by hybrid encryption, so any
 *   tampering inside the encrypted blob is detected on decryption.
 */
public class MeshPacket { //it represents what actually travels through the mesh network.

    @NotBlank
    private String packetId; // UUID, used by intermediates for gossip dedup.

    @Min(0) //means Negative TTL values are not allowed.
    private int ttl; // Time To Live or Hops remaining; intermediates decrement it, Every hop decreases TTL

    @NotNull
    private Long createdAt; // epoch millis, when sender created the packet, helps in diffrenciating the too old packets

    @NotBlank //Cannot be null or blank
    private String ciphertext; // base64(RSA-encrypted AES key + AES-GCM ciphertext),Everything important is here.(Encrypted AES Key+IV+Encrypted Payment)

    public MeshPacket() {}

    public String getPacketId() { return packetId; }
    public void setPacketId(String packetId) { this.packetId = packetId; }

    public int getTtl() { return ttl; }
    public void setTtl(int ttl) { this.ttl = ttl; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public String getCiphertext() { return ciphertext; }
    public void setCiphertext(String ciphertext) { this.ciphertext = ciphertext; }
}
