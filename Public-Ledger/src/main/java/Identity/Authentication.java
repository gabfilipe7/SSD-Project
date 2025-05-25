package Identity;

import java.io.*;
import java.nio.charset.StandardCharsets;
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

            KeyPair result  = new KeyPair(publicKey, privateKey);

            if(validateKeyPair(result)){
                return new KeyPair(publicKey, privateKey);
            }

            return null;
        }
    }

    public static boolean validateKeyPair(KeyPair keyPair) {

        try{
            byte[] message = "Validation message".getBytes(StandardCharsets.UTF_8);

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(message);
            byte[] signedData = signature.sign();

            signature.initVerify(keyPair.getPublic());
            signature.update(message);
            return signature.verify(signedData);

        }
        catch (Exception e){
            return false;
        }
    }


    public static boolean keysExist() {
        return new File(KEY_FILE).exists();
    }
}
