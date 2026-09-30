package org.entermediadb.websocket.push;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;
import javax.crypto.KeyAgreement;

public class WebPushEncryptor {

    public static byte[] encrypt(byte[] payload, byte[] clientP256dh, byte[] clientAuth) throws Exception {
        // 1. Generate local Ephemeral KeyPair (secp256r1 / P-256)
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair senderKeyPair = kpg.generateKeyPair();

        // 2. Parse client uncompressed public key (65 bytes)
        ECPublicKey clientPublicKey = parseUncompressedECPublicKey(clientP256dh);

        // 3. Compute Shared ECDH Secret (32 bytes)
        KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
        keyAgreement.init(senderKeyPair.getPrivate());
        keyAgreement.doPhase(clientPublicKey, true);
        byte[] ecdhSecret = keyAgreement.generateSecret();

        // 4. Generate 16-byte random salt
        byte[] salt = new byte[16];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);

        // 5. Get sender uncompressed public key (65 bytes)
        byte[] senderPublicKeyBytes = getUncompressedPublicKey((ECPublicKey) senderKeyPair.getPublic());

        // 6. HKDF Step 1: Derive PRK_key (RFC 8291 Section 3.4)
        // HKDF-Extract(salt = clientAuth, IKM = ecdhSecret)
        byte[] prkKey = hkdfExtract(clientAuth, ecdhSecret);

        // HKDF-Expand(PRK_key, info = "WebPush: info\0" + ua_public + as_public, L = 32)
        // The public-key context belongs here, not in the CEK/nonce info below.
        ByteArrayOutputStream webPushInfoOut = new ByteArrayOutputStream();
        webPushInfoOut.write("WebPush: info\0".getBytes(StandardCharsets.US_ASCII));
        webPushInfoOut.write(clientP256dh);
        webPushInfoOut.write(senderPublicKeyBytes);
        byte[] ikm = hkdfExpand(prkKey, webPushInfoOut.toByteArray(), 32);

        // 7. HKDF Step 2: Derive PRK using the 16-byte random salt
        // HKDF-Extract(salt = salt, IKM = ikm)
        byte[] prk = hkdfExtract(salt, ikm);

        // Derive Content Encryption Key (CEK - 16 bytes) and Nonce (12 bytes).
        // Per RFC 8188 aes128gcm these use fixed labels with no key context.
        byte[] cek = hkdfExpand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdfExpand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        // 8. Format Record: Plaintext Payload + 0x02 Delimiter Byte (RFC 8188)
        byte[] record = new byte[payload.length + 1];
        System.arraycopy(payload, 0, record, 0, payload.length);
        record[payload.length] = 0x02; // Record delimiter for single/final record

        // 9. AES-128-GCM Encryption
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(cek, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonce);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
        byte[] cipherText = cipher.doFinal(record);

        // 10. Construct aes128gcm Header Block (RFC 8188 Section 2.1)
        // [16-byte salt] + [4-byte record size (4096)] + [1-byte key_id len (65)] + [65-byte sender key] + [ciphertext]
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(salt);
        out.write(ByteBuffer.allocate(4).putInt(4096).array());
        out.write((byte) senderPublicKeyBytes.length); // 65
        out.write(senderPublicKeyBytes);
        out.write(cipherText);

        return out.toByteArray();
    }

    private static byte[] hkdfExtract(byte[] salt, byte[] ikm) throws Exception {
        byte[] actualSalt = (salt == null || salt.length == 0) ? new byte[32] : salt;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(actualSalt, "HmacSHA256"));
        return mac.doFinal(ikm);
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1); // Counter byte T(1)
        byte[] result = mac.doFinal();
        return Arrays.copyOf(result, length);
    }

    private static ECPublicKey parseUncompressedECPublicKey(byte[] raw) throws Exception {
        if (raw == null || raw.length != 65 || raw[0] != 0x04) {
            throw new IllegalArgumentException("Invalid uncompressed P-256 key format. Expected 65 bytes starting with 0x04.");
        }
        byte[] xBytes = Arrays.copyOfRange(raw, 1, 33);
        byte[] yBytes = Arrays.copyOfRange(raw, 33, 65);

        BigInteger x = new BigInteger(1, xBytes);
        BigInteger y = new BigInteger(1, yBytes);
        ECPoint point = new ECPoint(x, y);

        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec ecSpec = parameters.getParameterSpec(ECParameterSpec.class);

        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        return (ECPublicKey) keyFactory.generatePublic(new ECPublicKeySpec(point, ecSpec));
    }

    private static byte[] getUncompressedPublicKey(ECPublicKey key) {
        byte[] x = key.getW().getAffineX().toByteArray();
        byte[] y = key.getW().getAffineY().toByteArray();
        byte[] uncompressed = new byte[65];
        uncompressed[0] = 0x04;
        
        System.arraycopy(x, Math.max(0, x.length - 32), uncompressed, 1 + Math.max(0, 32 - x.length), Math.min(32, x.length));
        System.arraycopy(y, Math.max(0, y.length - 32), uncompressed, 33 + Math.max(0, 32 - y.length), Math.min(32, y.length));
        
        return uncompressed;
    }
}