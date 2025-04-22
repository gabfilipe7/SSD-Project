package Kademlia;

import Blockchain.Transaction;
import com.google.protobuf.ByteString;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Security;
import java.security.spec.X509EncodedKeySpec;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

public class Utils implements Comparator<Node> {

    private Node currentNode;

    public Utils(Node currentNode) {
        this.currentNode = currentNode;
    }

    @Override
    public int compare(Node node1, Node node2) {
        BigInteger distance1 = currentNode.xorDistance(node1.getId());
        BigInteger distance2 = currentNode.xorDistance(node2.getId());

        return distance1.compareTo(distance2);
    }

    private List<Node> sortByDistance(List<Node> peers, BigInteger targetId) {
        return peers.stream()
                .sorted(Comparator.comparing(peer -> peer.getId().xor(targetId)))
                .collect(Collectors.toList());
    }

    public static PublicKey byteStringToPublicKey(ByteString byteString) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        byte[] keyBytes = byteString.toByteArray();
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA", "BC");
        return keyFactory.generatePublic(spec);
    }

    public static byte[] serialize(Transaction tx)  {
        try{
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(bos);
            out.writeObject(tx);
            return bos.toByteArray();
        }
        catch (Exception e){
            return null;
        }
    }

    public static Transaction deserialize(byte[] data) {
        try
        {
            ByteArrayInputStream bis = new ByteArrayInputStream(data);
            ObjectInputStream in = new ObjectInputStream(bis);
            return (Transaction) in.readObject();
        }
        catch (Exception e){
            return null;
        }
    }

}
