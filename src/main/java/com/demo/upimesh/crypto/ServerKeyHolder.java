package com.demo.upimesh.crypto;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * Holds the server's RSA keypair.
 *
 * In production, the private key would live in an HSM (Hardware Security Module)
 * or at least a KMS like AWS KMS / HashiCorp Vault. NEVER in the JAR or source.
 *
 * For this demo we generate a fresh keypair on every startup. The public key is
 * exposed via /api/server-key so the (simulated) sender devices can use it to
 * encrypt payloads.
 */
@Component //Spring, please create exactly one object of this class and manage its lifecycle.
public class ServerKeyHolder {

    private static final Logger log = LoggerFactory.getLogger(ServerKeyHolder.class);

    private KeyPair keyPair;

    @PostConstruct //It ensures the RSA key pair is generated automatically after Spring creates the bean and before any requests are handled.
    public void init() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");//Create an RSA key generator. It does not generate keys yet. It only prepares the generator.
        gen.initialize(2048);//Generate a 2048-bit RSA key pair.
        this.keyPair = gen.generateKeyPair();//Now Java creates: Public Key + Private Key. and stores both in keypair
        log.info("Server RSA keypair generated (2048-bit). Public key fingerprint: {}",
                getPublicKeyBase64().substring(0, 32) + "..."); //It doesn't print the whole key. prints only the first 32 characters.(MIIBIjANBgkqhki...) This is enough to identify the key in logs without flooding the console.
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    } //This returns the public key. sender uses this

    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    } //Only the server should ever use it. If someone steals the private key, they can decrypt every incoming payment.

    public String getPublicKeyBase64() { //Base64 converts: binary to text
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }
}
