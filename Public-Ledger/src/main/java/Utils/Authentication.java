package Utils;

import java.io.*;
import java.security.*;
import java.security.spec.*;

public class Authentication {

    private static final String KEY_FILE = "authentication_keys.dat";


    public static void saveKeyPair(KeyPair keyPair) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(KEY_FILE))) {
            byte[] pubBytes = keyPair.getPublic().getEncoded();
            dos.writeInt(pubBytes.length);
            dos.write(pubBytes);

            byte[] privBytes = keyPair.getPrivate().getEncoded();
            dos.writeInt(privBytes.length);
            dos.write(privBytes);
        }
    }

    public static KeyPair loadKeyPair(String algorithm) throws IOException, GeneralSecurityException {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(KEY_FILE))) {
            int pubLength = dis.readInt();
            byte[] pubBytes = new byte[pubLength];
            dis.readFully(pubBytes);


            int privLength = dis.readInt();
            byte[] privBytes = new byte[privLength];
            dis.readFully(privBytes);

            X509EncodedKeySpec pubKeySpec = new X509EncodedKeySpec(pubBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
            PublicKey publicKey = keyFactory.generatePublic(pubKeySpec);

            PKCS8EncodedKeySpec privKeySpec = new PKCS8EncodedKeySpec(privBytes);
            PrivateKey privateKey = keyFactory.generatePrivate(privKeySpec);

            return new KeyPair(publicKey, privateKey);
        }
    }

    public static boolean keysExist() {
        return new File(KEY_FILE).exists();
    }
}
