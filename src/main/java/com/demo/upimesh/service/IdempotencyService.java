package com.demo.upimesh.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency cache. In production this would be Redis with SETNX +
 * TTL — exactly the same semantics, just distributed across instances.
 *
 * The contract:
 *   - claim(hash) returns true on first call, false on every call after that
 *     (within the TTL window)
 *   - the operation is atomic — even if 100 threads call claim(hash) at the
 *     same instant, exactly one returns true
 *
 * This is what kills the "three bridges deliver simultaneously" problem.
 * ConcurrentHashMap.putIfAbsent is the JVM-local equivalent of Redis SETNX.
 */
//Idempotency means that the same request is processed only once, even if it is received multiple times.
//Before settlement, the server asks: "Have I already processed this payment?"
@Service
public class IdempotencyService {

    private final Map<String, Instant> seen = new ConcurrentHashMap<>(); //it stores which packets have already been processed so they aren't processed again.
    //Key (String) → Packet Hash , Value (Instant) → Time when it was first processed

    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds; //if ttl is 86400 seconds = 24 hours this simply means Keep every processed packet hash for 24 hours. After that, delete it.

    /**
     * Try to claim a hash. Returns true if this caller is the first; false if
     * someone else already claimed it (i.e. the packet is a duplicate).
     */
    public boolean claim(String packetHash) {
        Instant now = Instant.now(); //Gets the current time. eg-10:30 AM
        Instant prev = seen.putIfAbsent(packetHash, now); //agr map me vo packet phle se ni hai to add krdo wrna agr phle se hai to mt kro
        return prev == null;
    }

    public int size() {
        return seen.size();
    } //returns How many packet hashes are stored?

    /** Periodically evict entries past their TTL so the map doesn't grow forever. */
    @Scheduled(fixedDelay = 60_000) //This method automatically runs every 60 seconds (1 minute).
    public void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(ttlSeconds); //Suppose the current time is 2:00 PM and the TTL is 24 hours, so the cutoff time becomes yesterday at 2:00 PM.
        seen.entrySet().removeIf(e -> e.getValue().isBefore(cutoff)); //removes every packet older than the cutoff.
    }

    /** Test/demo helper. */
    public void clear() {
        seen.clear();
    } //Deletes everything. Usually used for testing or restarting the demo.
}
