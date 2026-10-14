package com.megarohas.tvvoice;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/**
 * The tablet's TLS identity towards the TV: an RSA key that never leaves the
 * Android Keystore, with the self-signed certificate the Keystore makes for it.
 * The TV remembers this certificate at pairing and accepts it from then on.
 */
final class Identity {

    private static final String ALIAS = "tvremote-tls";

    private Identity() {
    }

    private static synchronized KeyStore keystore() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            long now = System.currentTimeMillis();
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            // DECRYPT with no padding as well: for RSA-PSS (TLS 1.3) Conscrypt pads by itself
            // and asks the Keystore only for the raw private-key operation.
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_MD5, KeyProperties.DIGEST_SHA1,
                            KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                            KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE,
                            KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setRandomizedEncryptionRequired(false)
                    .setCertificateSubject(new X500Principal("CN=tvvoice"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(new Date(now - 86_400_000L))
                    .setCertificateNotAfter(new Date(now + 30L * 365 * 86_400_000L))
                    .build());
            g.generateKeyPair();
            if (ks.containsAlias("tvremote")) ks.deleteEntry("tvremote"); // first build's sign-only key
        }
        return ks;
    }

    static RSAPublicKey publicKey() throws Exception {
        return (RSAPublicKey) keystore().getCertificate(ALIAS).getPublicKey();
    }

    /**
     * TLS to the TV with our client certificate. The TV's own certificate is
     * self-signed, so it's accepted here and pinned by fingerprint after pairing.
     */
    static SSLSocket connect(String host, int port, int timeoutMs) throws Exception {
        KeyStore ks = keystore();
        final PrivateKey key = (PrivateKey) ks.getKey(ALIAS, null);
        final X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);
        X509KeyManager km = new X509KeyManager() {
            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return new String[]{ALIAS};
            }

            @Override
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                return ALIAS; // whatever CAs the TV lists, this is the certificate it paired with
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return null;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return new X509Certificate[]{cert};
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return key;
            }
        };
        X509TrustManager any = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{km}, new TrustManager[]{any}, null);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            s.startHandshake();
            return s;
        } catch (Exception e) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    static Certificate peer(SSLSocket s) throws Exception {
        return s.getSession().getPeerCertificates()[0];
    }

    static String fingerprint(Certificate c) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(c.getEncoded())) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
