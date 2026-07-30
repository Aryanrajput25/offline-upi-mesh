package com.demo.upimesh.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Permanent record of every settled transaction. Once written, never modified.
 * The packetHash is the idempotency key — uniqueness is enforced at the DB level
 * as a defense-in-depth fallback if the Redis-style cache layer ever fails.
 */
@Entity //this tells spring that "This class should be stored as a table in the database."
@Table(name = "transactions",
        indexes = { @Index(name = "idx_packet_hash", columnList = "packetHash", unique = true) })
public class Transaction { //This class represents one payment record. Store every transaction as a row in the transaction table.

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; //Every transaction gets a unique ID.

    @Column(nullable = false, unique = true, length = 64)
    private String packetHash; // This stores SHA-256 hex of the encrypted packet

    @Column(nullable = false)
    private String senderVpa; //Who sent the money.

    @Column(nullable = false)
    private String receiverVpa; //Who received the money.

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount; //Again, BigDecimal is used for precise financial calculations.

    @Column(nullable = false)
    private Instant signedAt; // When the sender originally signed it (offline). This is the time when the user created the payment.

    @Column(nullable = false)
    private Instant settledAt; // When the backend actually processed it. This is the time when the server completed the payment.

    @Column(nullable = false)
    private String bridgeNodeId; // Which mesh node finally delivered it. Which bridge device uploaded this payment?

    @Column(nullable = false)
    private int hopCount; // How many devices it passed through.

    @Enumerated(EnumType.STRING) //An enum allows only predefined constant values (e.g., SUCCESS, REJECTED), preventing typos and invalid states while improving code readability and reliability.
    @Column(nullable = false)
    private Status status; //Only two possible values- success and reject.

    public enum Status { SETTLED, REJECTED }

    public Transaction() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPacketHash() { return packetHash; }
    public void setPacketHash(String packetHash) { this.packetHash = packetHash; }

    public String getSenderVpa() { return senderVpa; }
    public void setSenderVpa(String senderVpa) { this.senderVpa = senderVpa; }

    public String getReceiverVpa() { return receiverVpa; }
    public void setReceiverVpa(String receiverVpa) { this.receiverVpa = receiverVpa; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public Instant getSignedAt() { return signedAt; }
    public void setSignedAt(Instant signedAt) { this.signedAt = signedAt; }

    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }

    public String getBridgeNodeId() { return bridgeNodeId; }
    public void setBridgeNodeId(String bridgeNodeId) { this.bridgeNodeId = bridgeNodeId; }

    public int getHopCount() { return hopCount; }
    public void setHopCount(int hopCount) { this.hopCount = hopCount; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
}
