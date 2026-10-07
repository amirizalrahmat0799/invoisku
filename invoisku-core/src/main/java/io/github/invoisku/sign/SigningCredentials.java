package io.github.invoisku.sign;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The private key and certificate used to sign e-invoices: the digital certificate issued to the taxpayer by an
 * LHDN-approved certificate authority, usually delivered as a PKCS#12 (.p12 / .pfx) file.
 */
public final class SigningCredentials {

    private final PrivateKey key;
    private final X509Certificate certificate;

    public SigningCredentials(PrivateKey key, X509Certificate certificate) {
        this.key = Objects.requireNonNull(key, "key");
        this.certificate = Objects.requireNonNull(certificate, "certificate");
    }

    public PrivateKey key() {
        return key;
    }

    public X509Certificate certificate() {
        return certificate;
    }

    /** Loads the first key entry (or the entry with {@code alias}) from a PKCS#12 file. */
    public static SigningCredentials fromPkcs12(Path file, char[] password) throws IOException, GeneralSecurityException {
        try (InputStream in = Files.newInputStream(file)) {
            return fromPkcs12(in, password, null);
        }
    }

    public static SigningCredentials fromPkcs12(InputStream in, char[] password, String alias)
            throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(in, password);
        String chosen = alias;
        if (chosen == null) {
            for (String a : Collections.list(ks.aliases())) {
                if (ks.isKeyEntry(a)) {
                    chosen = a;
                    break;
                }
            }
        }
        if (chosen == null || !ks.isKeyEntry(chosen)) {
            throw new GeneralSecurityException("No private key entry in the PKCS#12 file");
        }
        PrivateKey key = (PrivateKey) ks.getKey(chosen, password);
        X509Certificate cert = (X509Certificate) ks.getCertificate(chosen);
        return new SigningCredentials(key, cert);
    }

    /**
     * Loads a PEM certificate and a PEM private key (PKCS#8 "PRIVATE KEY" or PKCS#1 "RSA PRIVATE KEY", unencrypted).
     */
    public static SigningCredentials fromPem(String certificatePem, String privateKeyPem) throws GeneralSecurityException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(
                new java.io.ByteArrayInputStream(certificatePem.getBytes(StandardCharsets.US_ASCII)));
        byte[] der;
        boolean pkcs1;
        Matcher m8 = pem("PRIVATE KEY").matcher(privateKeyPem);
        Matcher m1 = pem("RSA PRIVATE KEY").matcher(privateKeyPem);
        if (m1.find()) {
            der = Base64.getMimeDecoder().decode(m1.group(1));
            pkcs1 = true;
        } else if (m8.find()) {
            der = Base64.getMimeDecoder().decode(m8.group(1));
            pkcs1 = false;
        } else {
            throw new GeneralSecurityException("No unencrypted PEM private key found");
        }
        if (pkcs1) der = wrapPkcs1(der);
        String alg = cert.getPublicKey().getAlgorithm();
        PrivateKey key = KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(der));
        return new SigningCredentials(key, cert);
    }

    private static Pattern pem(String label) {
        return Pattern.compile("-----BEGIN " + label + "-----([A-Za-z0-9+/=\\s]+)-----END " + label + "-----");
    }

    /** PKCS#1 RSAPrivateKey -> PKCS#8 PrivateKeyInfo. */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] algId = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01,
            0x01, 0x05, 0x00};
        byte[] version = {0x02, 0x01, 0x00};
        byte[] octet = tlv(0x04, pkcs1);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(version);
        body.writeBytes(algId);
        body.writeBytes(octet);
        return tlv(0x30, body.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 0x80) {
            out.write(len);
        } else {
            int bytes = len > 0xffffff ? 4 : len > 0xffff ? 3 : len > 0xff ? 2 : 1;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) out.write((len >>> (8 * i)) & 0xff);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }
}
