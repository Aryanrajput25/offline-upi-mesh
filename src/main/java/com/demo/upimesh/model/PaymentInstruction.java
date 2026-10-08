package com.demo.upimesh.model;

import java.math.BigDecimal;

/**
 * The actual payment instruction. After the server decrypts MeshPacket.ciphertext,
 * it gets one of these.
 *
 * Critical fields for security:
 *   - nonce: a UUID unique to this payment. Even if everything else were identical
 *            for two legitimate payments (alice sends bob ₹100 twice), the nonces
 *            differ, so the resulting ciphertexts and their hashes also differ.
 *   - signedAt: lets the server reject stale packets ("freshness window"). Without
 *               this, an attacker who got the ciphertext could replay it weeks later.
 *   - pinHash: in a real system the user enters a UPI PIN; we'd verify it against
 *              a hash held by the bank. Here we just record it for realism.
 */
//contains What is being paid?
public class PaymentInstruction { // Before encryption, every payment is stored in this object.
                                    //So this class is created twice: Before encryption (sender side) and After decryption (server side)
    private String senderVpa; //Instead of using bank account numbers, UPI uses VPAs. like aryan@oksbi
    private String receiverVpa;
    private BigDecimal amount; //BigDecimal stores decimal numbers with high precision
    private String pinHash; //Imagine your PIN is 1234, server stores something like 8d969eef6ecad3c29a...

    //nonce- It guarantees that every payment request is unique. Even if the same sender transfers the same amount to the same receiver multiple times, each payment has a different nonce, preventing replay attacks and identical ciphertext.
    private String nonce;     // UUID, unique per payment intent. nonce=Number used once eg-550e8400-e29b-41d4-a716-446655440000 Every payment gets a different nonce. it helps in diffrenciating the identical payments details

    //signedAt-It allows the server to reject stale or replayed packets by checking whether the payment was created within an acceptable time window.
    private Long signedAt;    // epoch millis, when sender signed means The exact time when the payment was created. (eg-1753487212000)

    public PaymentInstruction() {}  //Jackson (the JSON library) needs it when converting JSON back into a Java object.

    public PaymentInstruction(String senderVpa, String receiverVpa, BigDecimal amount,
                              String pinHash, String nonce, Long signedAt) {
        this.senderVpa = senderVpa;
        this.receiverVpa = receiverVpa;
        this.amount = amount;
        this.pinHash = pinHash;
        this.nonce = nonce;
        this.signedAt = signedAt;
    }

    public String getSenderVpa() { return senderVpa; }
    public void setSenderVpa(String senderVpa) { this.senderVpa = senderVpa; }

    public String getReceiverVpa() { return receiverVpa; }
    public void setReceiverVpa(String receiverVpa) { this.receiverVpa = receiverVpa; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getPinHash() { return pinHash; }
    public void setPinHash(String pinHash) { this.pinHash = pinHash; }

    public String getNonce() { return nonce; }
    public void setNonce(String nonce) { this.nonce = nonce; }

    public Long getSignedAt() { return signedAt; }
    public void setSignedAt(Long signedAt) { this.signedAt = signedAt; }
}
