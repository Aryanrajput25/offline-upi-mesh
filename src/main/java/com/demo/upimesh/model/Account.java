package com.demo.upimesh.model;

import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * Simulated bank account. In a real system this would live in the bank's core,
 * not in our service. For the demo, we own the ledger.
 */
@Entity //this tells spring that "This class should be stored as a table in the database."
@Table(name = "accounts")
public class Account {

    @Id //Spring automatically generates these IDs. like 1 2 3 4...You never manually write:
    private String vpa; // Virtual Payment Address, e.g. "alice@demo"

    @Column(nullable = false)
    private String holderName; //Only for display. The system actually searches using the VPA, not the owner's name.

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance; //This stores the money. bigdecimal is Exactly correct for adding decimal numbers

    @Version  // Optimistic locking — prevents lost updates on concurrent transfers
    private Long version;

    public Account() {}

    public Account(String vpa, String holderName, BigDecimal balance) {
        this.vpa = vpa;
        this.holderName = holderName;
        this.balance = balance;
    }

    public String getVpa() { return vpa; }
    public void setVpa(String vpa) { this.vpa = vpa; }

    public String getHolderName() { return holderName; }
    public void setHolderName(String holderName) { this.holderName = holderName; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
