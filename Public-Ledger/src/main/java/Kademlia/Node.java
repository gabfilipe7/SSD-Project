package Kademlia;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.*;

import Kademlia.KBucket;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9ECParametersHolder;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public class Node {

    private BigInteger nodeId;
    private String ipAddress;
    private int K;
    private int port;
    private ArrayList<KBucket> routingTable;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public Node(String ipAddress, int port, int k) {
        this.ipAddress = ipAddress;
        this.port = port;

        this.routingTable = new ArrayList<>(256);
        for (int i = 0; i < 256; i++) {
            this.routingTable.add(new KBucket(k));
        }

        try{
            KeyPair keys = generateKeys();
            byte[] encodedPK = keys.getPublic().getEncoded();

            MessageDigest digest = MessageDigest.getInstance("SHA-256", "BC");
            byte[] hashBytes = digest.digest(encodedPK);


            this.nodeId = new BigInteger(1, hashBytes);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }


    }

    public BigInteger xorDistance(BigInteger other) {
        return this.nodeId.xor(other);
    }

    public int getTargetBucketIndex(BigInteger nodeID) {
        BigInteger distance = this.xorDistance(nodeID);
        int index = distance.bitLength() - 1;
        return (index < 0) ? 0 : (index > 255) ? 255 : index;
    }

    public boolean addNode(Node node) {
        int index = getTargetBucketIndex(node.getId());
        KBucket bucket = this.routingTable.get(index);
        boolean success = bucket.addNode(node);
        return success;
    }

    public BigInteger getId() {
        return this.nodeId;
    }

    public boolean ping(Node node) {
        System.out.println("Pinging node: " + node.getId());
        return true;
    }

    /*public List<Node> findClosestNodes(BigInteger targetNodeId, int sizeNumber) {

        int index = getTargetBucketIndex(targetNodeId);
        KBucket bucket = this.routingTable.get(index);

        List<Node> closestNodes = new ArrayList<>(bucket.getNodes());

        Utils nodeComparator = new Utils(this);

        closestNodes.sort(nodeComparator);

        return closestNodes.subList(0, Math.min(sizeNumber, closestNodes.size()));
    }*/

    public List<Node> findClosestNodes(BigInteger targetId) {
        List<Node> orderedNodes = new ArrayList<>();

        for (KBucket bucket : this.routingTable) {
            orderedNodes.addAll(bucket.getNodes());
        }

        Utils nodeComparator = new Utils(this);

        orderedNodes.sort(nodeComparator);

        return orderedNodes.subList(0, this.K);
    }

    public List<Node> findNode(BigInteger targetNodeId) {
        Set<Node> nodesToQuery = new HashSet<>();
        List<Node> resultNodes = new ArrayList<>();

        List<Node> closestNodes = findClosestNodes(targetNodeId);
        nodesToQuery.addAll(closestNodes);


        Set<Node> queriedNodes = new HashSet<>();

        while (!nodesToQuery.isEmpty() && resultNodes.size() < this.K) {

            Node node = nodesToQuery.iterator().next();
            nodesToQuery.remove(node);


            if (queriedNodes.contains(node)) {
                continue;
            }

            queriedNodes.add(node);


            List<Node> nodesFromResponse = sendFindNodeRequest(node, targetNodeId);

            for (Node newNode : nodesFromResponse) {
                if (!queriedNodes.contains(newNode)) {
                    nodesToQuery.add(newNode);
                }
            }

            for (Node newNode : nodesFromResponse) {
                if (!resultNodes.contains(newNode) && !newNode.getId().equals(targetNodeId)) {
                    resultNodes.add(newNode);
                }
            }
        }

        return resultNodes;
    }

    private List<Node> sendFindNodeRequest(Node node, BigInteger targetNodeId) {

        return null;
    }



    private static KeyPair generateKeys()  {

        try{

        ECGenParameterSpec parameters = new ECGenParameterSpec("secp256r1");

        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC", "BC");
        keyPairGenerator.initialize(parameters);

        KeyPair keys = keyPairGenerator.generateKeyPair();

        return keys;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }



}