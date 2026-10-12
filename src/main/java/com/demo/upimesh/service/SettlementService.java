package com.demo.upimesh.service;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Where the actual ledger update happens. Wrapped in a DB transaction so either
 * BOTH the debit and credit happen, or neither does.
 *
 * The @Version column on Account gives us optimistic locking — if two threads
 * somehow get past idempotency and both try to debit the same account, the
 * second one will fail with OptimisticLockException rather than corrupting
 * the balance. (In a demo the idempotency layer should always catch this first,
 * but defense in depth.)
 */
//@Transactional ensures that the debit, credit and successful ledger entry participate in one database transaction. This prevents a failure from leaving only one side of the transfer committed.
//this is where actually moves money, this is where account balances are updated.
//Once BridgeIngestionService has decrypted the payment and checked its freshness, it calls SettlementService
@Service //tells Spring to manage this class as a service bean.
public class SettlementService { //Now the server has decrypted the payment and asks: "Can I complete this payment?"

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    @Autowired private AccountRepository accounts; //database of Accounts Table means in db there is accounts for each person
    @Autowired private TransactionRepository transactions; //This stores payment history.

    @Transactional //It tells Spring to execute the database operations within a database transaction.
    //Either all the necessary database changes commit together, or a failure that triggers rollback prevents those changes from being partially committed.
    public Transaction settle(PaymentInstruction instruction, String packetHash, //PaymentInstruction contains sender,receiver,amount, pin hash, nonce.
                              String bridgeNodeId, int hopCount) {
        //Find the sender and receiver
        Account sender = accounts.findById(instruction.getSenderVpa()) //finds sender
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown sender VPA: " + instruction.getSenderVpa()));

        Account receiver = accounts.findById(instruction.getReceiverVpa())//finds receiver
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown receiver VPA: " + instruction.getReceiverVpa()));

        BigDecimal amount = instruction.getAmount(); //BigDecimal because it supports precise decimal arithmetic for monetary values. double can introduce rounding errors
        if (amount.signum() <= 0) { //signum() tells us whether a number is negative, zero or positive: -1, 0, 1 so if it is negative or zero then throws exception
            throw new IllegalArgumentException("Amount must be positive"); //amount wants to send must be positive integer
        }

        //Check the sender's balance                5000.compareTo(500) = 1 and 300.compareTo(500) = -1
        if (sender.getBalance().compareTo(amount) < 0) { //check balance first, if sender wants to send more amount than the balance amount
            log.warn("Insufficient balance: {} has ₹{}, tried to send ₹{}",
                    sender.getVpa(), sender.getBalance(), amount);
            return recordRejected(instruction, packetHash, bridgeNodeId, hopCount); //this calls recordRejected()
        }

        sender.setBalance(sender.getBalance().subtract(amount)); //update the senders new balance
        receiver.setBalance(receiver.getBalance().add(amount)); //update the receivers new balance
        accounts.save(sender);
        accounts.save(receiver);

        //Create the transaction record
        Transaction tx = new Transaction(); //A transaction object is created. Something like- Sender: Aryan | Receiver: Rahul | Amount: £500 | Status: SUCCESS | Time: Current Time
        tx.setPacketHash(packetHash); //packetHash - Fingerprint used to identify the encrypted packet
        tx.setSenderVpa(instruction.getSenderVpa());
        tx.setReceiverVpa(instruction.getReceiverVpa());
        tx.setAmount(amount);
        tx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
        tx.setSettledAt(Instant.now());
        tx.setBridgeNodeId(bridgeNodeId);
        tx.setHopCount(hopCount);
        tx.setStatus(Transaction.Status.SETTLED);
        transactions.save(tx); //Payment history is stored.

        log.info("SETTLED ₹{} from {} to {} (packetHash={}, bridge={}, hops={})",
                amount, sender.getVpa(), receiver.getVpa(),
                packetHash.substring(0, 12) + "...", bridgeNodeId, hopCount);

        return tx;
    }

    //Neither account is modified by this path.
    private Transaction recordRejected(PaymentInstruction instruction, String packetHash,
                                       String bridgeNodeId, int hopCount) { //This method is called when a payment is rejected
        Transaction tx = new Transaction(); //Creates a new Transaction object
        tx.setPacketHash(packetHash); //Fills it with all payment details
        tx.setSenderVpa(instruction.getSenderVpa());
        tx.setReceiverVpa(instruction.getReceiverVpa());
        tx.setAmount(instruction.getAmount());
        tx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
        tx.setSettledAt(Instant.now());
        tx.setBridgeNodeId(bridgeNodeId);
        tx.setHopCount(hopCount);
        tx.setStatus(Transaction.Status.REJECTED); //Marks the transaction as REJECTED and saves it
        return transactions.save(tx);
    }
}
