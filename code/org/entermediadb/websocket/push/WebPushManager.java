/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.entermediadb.websocket.push;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.EnumSet;
import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.ModuleManager;
import org.openedit.util.HttpSharedConnection;

/**
 * Sends browser push notifications (Web Push, RFC 8291/8292) using the aes128gcm content
 * encoding. The payload is encrypted with a per-message ephemeral P-256 ECDH keypair and
 * authenticated with a VAPID ES256 JWT. Uses only the JDK crypto providers plus the
 * httpclient jar already on the plugin classpath.
 */
public class WebPushManager
{
	private static final Log log = LogFactory.getLog(WebPushManager.class);

	/** Searcher type holding one record per user with their browser push endpoint. */
	public static final String ENDPOINT_SEARCHTYPE = "usernotificationendpoint";

	/** 28 days, the maximum TTL accepted by FCM. */
	private static final int TTL_SECONDS = 86400;

	private ModuleManager fieldModuleManager;
	private KeyPair fieldVapidKeyPair;
	private final SecureRandom fieldRandom = new SecureRandom();

	public void setModuleManager(ModuleManager inModuleManager)
	{
		fieldModuleManager = inModuleManager;
	}

	public ModuleManager getModuleManager()
	{
		return fieldModuleManager;
	}

	// ------------------------------------------------------------------
	// VAPID key management
	// ------------------------------------------------------------------

	/**
	 * Loads the VAPID P-256 key pair from $SERVERHOME/data/vapid (private.pk8 or private.pem,
	 * PKCS#8). If no key exists a new pair is generated and persisted there.
	 */
	public synchronized KeyPair getVapidKeyPair() throws Exception
	{
		if (fieldVapidKeyPair != null)
		{
			return fieldVapidKeyPair;
		}
		File vapidDir = new File(getServerHome(), "data/vapid");
		KeyPair keypair = loadKey(vapidDir);
		if (keypair == null)
		{
			KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
			generator.initialize(new ECGenParameterSpec("secp256r1"));
			keypair = generator.generateKeyPair();
			saveKey(vapidDir, keypair);
			log.info("Generated new VAPID key pair in " + vapidDir.getAbsolutePath());
		}
		fieldVapidKeyPair = keypair;
		return fieldVapidKeyPair;
	}

	public String getVapidPublicKeyBase64Url() throws Exception {
		KeyPair vapid = getVapidKeyPair();
		ECPublicKey publicKey = (ECPublicKey) vapid.getPublic();
		ECPoint w = publicKey.getW();

		// EC point coordinates X and Y on curve secp256r1 / P-256 (32 bytes each)
		byte[] x = w.getAffineX().toByteArray();
		byte[] y = w.getAffineY().toByteArray();

		// Create 65-byte uncompressed point: [0x04, 32-byte X, 32-byte Y]
		byte[] uncompressedPoint = new byte[65];
		uncompressedPoint[0] = 0x04; // Uncompressed indicator byte

		// Copy X coordinate (handle potential leading 0x00 byte from BigInteger)
		int xOffset = (x.length > 32) ? x.length - 32 : 0;
		int xLen = Math.min(x.length, 32);
		System.arraycopy(x, xOffset, uncompressedPoint, 1 + (32 - xLen), xLen);

		// Copy Y coordinate (handle potential leading 0x00 byte from BigInteger)
		int yOffset = (y.length > 32) ? y.length - 32 : 0;
		int yLen = Math.min(y.length, 32);
		System.arraycopy(y, yOffset, uncompressedPoint, 33 + (32 - yLen), yLen);

		// Encode as unpadded Base64URL
		return Base64.getUrlEncoder().withoutPadding().encodeToString(uncompressedPoint);
	}

	private String getServerHome()
	{
		String serverhome = System.getProperty("SERVERHOME");
		if (serverhome == null || serverhome.trim().isEmpty())
		{
			serverhome = System.getProperty("user.dir");
		}
		return serverhome;
	}

	/** Loads both the private (PKCS#8) and public (X.509) key files from the vapid directory. */
	private KeyPair loadKey(File inDir)
	{
		File privatefile = new File(inDir, "private.pk8");
		File publicfile = new File(inDir, "public.pk8");
		if (!privatefile.isFile() || !publicfile.isFile())
		{
			return null;
		}
		try
		{
			byte[] privateder = decodePem(privatefile);
			byte[] publicder = decodePem(publicfile);
			if (privateder == null || publicder == null)
			{
				return null;
			}
			KeyFactory factory = KeyFactory.getInstance("EC");
			PrivateKey privatekey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateder));
			PublicKey publickey = factory.generatePublic(new X509EncodedKeySpec(publicder));
			return new KeyPair(publickey, privatekey);
		}
		catch (Exception e)
		{
			log.warn("Could not load VAPID key from " + inDir.getAbsolutePath() + ": " + e.getMessage());
			return null;
		}
	}

	private void saveKey(File inDir, KeyPair inKeyPair) throws Exception
	{
		inDir.mkdirs();
		byte[] pk8 = inKeyPair.getPrivate().getEncoded();
		File pk8file = new File(inDir, "private.pk8");
		FileUtils.writeByteArrayToFile(pk8file, toPem("PRIVATE KEY", pk8));

		// Public key uses a different DER encoding (X.509) than the private key (PKCS#8),
		// so it must be persisted separately rather than re-derived from the private bytes.
		byte[] x509 = inKeyPair.getPublic().getEncoded();
		File publicfile = new File(inDir, "public.pk8");
		FileUtils.writeByteArrayToFile(publicfile, toPem("PUBLIC KEY", x509));
		try
		{
			// Note: the commons-io build on the plugin classpath lacks the posix permission helpers, use the JDK directly
			Files.setPosixFilePermissions(pk8file.toPath(), EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		}
		catch (Exception e)
		{
			log.warn("Could not set permissions on VAPID key files: " + e.getMessage());
		}
	}

	private static byte[] decodePem(File inFile) throws Exception
	{
		String pem = FileUtils.readFileToString(inFile, StandardCharsets.UTF_8);
		int begin = pem.indexOf("-----BEGIN");
		int end = pem.indexOf("-----END");
		if (begin < 0 || end < 0)
		{
			return null;
		}
		String base64 = pem.substring(begin, end).replaceAll("-----(BEGIN|END)[^-]+-----", "").replaceAll("\\s", "");
		return Base64.getDecoder().decode(base64);
	}

	private static byte[] toPem(String inLabel, byte[] inDer)
	{
		String base64 = Base64.getEncoder().encodeToString(inDer);
		StringBuilder sb = new StringBuilder();
		sb.append("-----BEGIN ").append(inLabel).append("-----\n");
		for (int i = 0; i < base64.length(); i += 64)
		{
			sb.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
		}
		sb.append("-----END ").append(inLabel).append("-----\n");
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	// ------------------------------------------------------------------
	// Push to a user's stored subscription
	// ------------------------------------------------------------------

	/**
	 * Sends a push notification to the browser subscription stored for the given user in the
	 * "userendpoint" table (one record per user, keyed by user id). If the push service answers
	 * 404/410 the subscription is expired and the record gets deleted.
	 *
	 * @return true if the push was accepted by the push service
	 */
	public boolean pushToUser(String inCatalogId, String inUserId, String inPayloadJson)
	{
		try
		{
			MediaArchive archive = (MediaArchive) getModuleManager().getBean(inCatalogId, "mediaArchive");
			Data endpointdata = archive.getData(ENDPOINT_SEARCHTYPE, inUserId);
			if (endpointdata == null)
			{
				return false;
			}
			String endpoint = (String) endpointdata.getValue("endpoint");
			if (endpoint == null || endpoint.trim().isEmpty())
			{
				return false;
			}
			byte[] p256dh = getP256dh(endpointdata);
			if (p256dh == null)
			{
				log.warn("Push subscription for user " + inUserId + " has no usable p256dh");
				return false;
			}
			byte[] auth = getAuth(endpointdata);
			if (auth == null)
			{
				log.warn("Push subscription for user " + inUserId + " has no usable auth secret");
				return false;
			}

			int status = push(endpoint, p256dh, auth, inPayloadJson);
			log.info("Web push to user " + inUserId + " returned HTTP " + status);
			if (status == 404 || status == 410)
			{
				// Expired subscription, drop it so we stop pushing to it
				archive.getSearcher(ENDPOINT_SEARCHTYPE).delete(endpointdata, null);
			}
			return status >= 200 && status < 300;
		}
		catch (Exception e)
		{
			log.error("Could not push to user " + inUserId, e);
			return false;
		}
	}

	public byte[] getAuth(Data inEndpoint) {
		String authStr = (String) inEndpoint.getValue("auth");
		if (authStr == null) {
			// Fallback check if the field name is capitalized or stored differently
			authStr = (String) inEndpoint.getValue("clientAuth"); 
		}
		return decodeBase64Key("auth", authStr, 16);
	}

	public byte[] getP256dh(Data inEndpoint) {
		String p256dhStr = (String) inEndpoint.getValue("p256dh");
		if (p256dhStr == null) {
			p256dhStr = (String) inEndpoint.getValue("clientP256dh");
		}
		return decodeBase64Key("p256dh", p256dhStr, 65);
	}

private byte[] decodeBase64Key(String keyName, String rawStr, int expectedBytes) {
    if (rawStr == null || rawStr.trim().isEmpty()) {
        log.error("CRITICAL: " + keyName + " string from DataRecord is NULL or EMPTY");
        return null;
    }

    try {
        String cleaned = rawStr.trim();

        // 1. Decode URL percent-encodings if present (e.g. %2B)
        if (cleaned.contains("%")) {
            cleaned = java.net.URLDecoder.decode(cleaned, StandardCharsets.UTF_8.name());
        }

        // 2. Replace any spaces back to '+' (form-urlencoded posts convert + to space)
        cleaned = cleaned.replace(" ", "+");

        // 3. Convert URL-safe Base64 chars to Standard Base64
        cleaned = cleaned.replace("-", "+").replace("_", "/");

        // 4. Pad if missing
        int missingPadding = (4 - (cleaned.length() % 4)) % 4;
        if (missingPadding > 0) {
            cleaned += "=".repeat(missingPadding);
        }

        byte[] decoded = Base64.getDecoder().decode(cleaned);

        if (expectedBytes > 0 && decoded.length != expectedBytes) {
            log.error("CRITICAL: " + keyName + " byte length mismatch! Expected " + expectedBytes + " bytes, got " + decoded.length);
        }

        return decoded;
    } catch (Exception e) {
        log.error("Failed to decode Base64 key [" + keyName + "]: '" + rawStr + "'", e);
        return null;
    }
}

	// ------------------------------------------------------------------
	// Core push: encrypt per RFC 8291 and POST with VAPID auth
	// ------------------------------------------------------------------

	/**
	 * Encrypts the payload with aes128gcm (RFC 8291) for the given subscriber p256dh key and
	 * posts it to the push endpoint with a VAPID bearer token (RFC 8292).
	 *
	 * @return the HTTP status code of the push service response
	 */
	public int push(String inEndpoint, byte[] inP256dh, byte[] inAuth, String inPayload) throws Exception
	{


		URI uri = new URI(inEndpoint);
		String origin = uri.getScheme() + "://" + uri.getHost(); 
		//log.info("Pushing notificacion from endpoint: " + origin);
		String jwt = buildVapidJwt(origin);

		HttpSharedConnection httpConnection = new HttpSharedConnection();

		String backendKey = getVapidPublicKeyBase64Url();
		log.info(backendKey);

		httpConnection.addSharedHeader("Authorization", "vapid t=" + jwt + ", k=" + backendKey);
		httpConnection.addSharedHeader("Content-Encoding", "aes128gcm");
		httpConnection.addSharedHeader("Content-Type", "application/octet-stream");
		httpConnection.addSharedHeader("TTL", Integer.toString(TTL_SECONDS));
		httpConnection.addSharedHeader("Topic", randomTopic());

		HttpPost post = new HttpPost(inEndpoint);
		log.info("Encrypting push payload for " + inEndpoint + ": " + inPayload);
		byte[] body = WebPushEncryptor.encrypt(
			inPayload.getBytes(StandardCharsets.UTF_8), 
			inP256dh, 
			inAuth
		);
		log.info("Encrypted push body length=" + body.length + " base64url=" + Base64.getUrlEncoder().withoutPadding().encodeToString(body));
		post.setEntity(new ByteArrayEntity(body));

		CloseableHttpResponse response = httpConnection.sharedPost(post);
		try
		{
			int status = response.getStatusLine().getStatusCode();
			// Drain the response so the connection can be reused
			org.apache.http.util.EntityUtils.consumeQuietly(response.getEntity());
			return status;
		}
		finally
		{
			httpConnection.release(response);
		}
	}

	/** Random 256-bit topic, base64url encoded, unique per push message. */
	private String randomTopic()
	{
		byte[] topic = new byte[32];
		fieldRandom.nextBytes(topic);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(topic);
	}

	private static int derLength(byte[] inBuf, int inOffset)
	{
		// returns offset after the length field
		int first = inBuf[inOffset] & 0xFF;
		if (first < 0x80)
		{
			return inOffset + 1;
		}
		int numbytes = first & 0x7F;
		return inOffset + 1 + numbytes;
	}

	// ------------------------------------------------------------------
	// VAPID JWT (RFC 8292)
	// ------------------------------------------------------------------

	String buildVapidJwt(String inAud) throws Exception
	{
		KeyPair vapid = getVapidKeyPair();
		long now = System.currentTimeMillis() / 1000L;

		JSONObject header = new JSONObject();
		header.put("typ", "JWT");
		header.put("alg", "ES256");

		JSONObject claims = new JSONObject();
		claims.put("aud", inAud);
		// Reduce expiration to 12 hours to stay well under the strict 24-hour RFC limit
		claims.put("exp", now + 43200L);
		
		String subject = System.getProperty("vapid.subject");
		if (subject == null || subject.trim().isEmpty())
		{
			subject = "mailto:webmaster@entermediadb.org";
		}
		claims.put("sub", subject);

		String signingInput = base64url(header.toJSONString().getBytes(StandardCharsets.UTF_8)) + "."
				+ base64url(claims.toJSONString().getBytes(StandardCharsets.UTF_8));

		Signature signature = Signature.getInstance("SHA256withECDSA");
		signature.initSign(vapid.getPrivate());
		signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
		
		byte[] rawSignature = derToRaw(signature.sign());
		
		return signingInput + "." + base64url(rawSignature);
	}


	/** Converts a DER-encoded ECDSA signature to the raw r||s (64 byte) form. */
	static byte[] derToRaw(byte[] inDer) throws Exception
	{
		if (inDer[0] != 0x30)
		{
			throw new IllegalArgumentException("not a DER sequence");
		}
		int i = derLength(inDer, 1);
		byte[] out = new byte[64];
		for (int component = 0; component < 2; component++)
		{
			if (inDer[i++] != 0x02)
			{
				throw new IllegalArgumentException("expected INTEGER");
			}
			int len = inDer[i++] & 0xFF;
			byte[] value = new byte[len];
			System.arraycopy(inDer, i, value, 0, len);
			i += len;
			// DER INTEGERs are big-endian and may carry a leading 0x00 pad byte to
			// keep the value positive; strip it so r/s fit in 32 bytes.
			int start = 0;
			if (value.length > 32)
			{
				if (value.length == 33 && value[0] == 0)
				{
					start = 1;
				}
				else
				{
					throw new IllegalArgumentException("DER integer too long");
				}
			}
			// left-pad to 32 bytes
			int pad = 32 - (value.length - start);
			System.arraycopy(value, start, out, component * 32 + pad, value.length - start);
		}
		return out;
	}

	private static String base64url(byte[] inBytes)
	{
		return Base64.getUrlEncoder().withoutPadding().encodeToString(inBytes);
	}
}
