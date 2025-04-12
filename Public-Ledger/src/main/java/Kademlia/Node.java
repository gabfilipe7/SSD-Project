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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

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
    private ExecutorService executorService;

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



    public List<Node> findClosestNodes(BigInteger targetNodeId, int sizeNumber) {
        List<Node> closestNodes = new ArrayList<>();

        int targetBucketIndex = getTargetBucketIndex(targetNodeId);

        int i = 0;
        int j = 1;
        Utils nodeComparator = new Utils(this);

        for (int it = 0; it < this.routingTable.size(); it++) {

            if (targetBucketIndex + i < this.routingTable.size()) {
                KBucket bucket = this.routingTable.get(targetBucketIndex + i);
                if (bucket != null && !bucket.getNodes().isEmpty()) {
                    for (Node node : bucket.getNodes()) {
                        if (closestNodes.size() < sizeNumber) {
                            closestNodes.add(node);
                        } else {
                            closestNodes.sort(nodeComparator);
                            return closestNodes.subList(0, sizeNumber);
                        }
                    }
                }
                i++;
            }

            if (targetBucketIndex - j >= 0) {
                KBucket bucket = this.routingTable.get(targetBucketIndex - j);
                if (bucket != null && !bucket.getNodes().isEmpty()) {
                    for (Node node : bucket.getNodes()) {
                        if (closestNodes.size() < sizeNumber) {
                            closestNodes.add(node);
                        } else {
                            closestNodes.sort(nodeComparator);
                            return closestNodes.subList(0, sizeNumber);
                        }
                    }
                }
                j++;
            }
        }

        if (closestNodes.isEmpty()) {
            return null;
        }

        closestNodes.sort(nodeComparator);

        return closestNodes.subList(0, Math.min(sizeNumber, closestNodes.size()));
    }

    public CompletableFuture<Optional<Node>> findNode(BigInteger targetId) {
        List<Node> initialPeers = findClosestNodes(targetId, this.K);
        Set<Node> alreadyChecked = new HashSet<>();

        return findNodeRecursive(targetId, initialPeers, alreadyChecked,20);
    }

    private CompletableFuture<Optional<Node>> findNodeRecursive(
            BigInteger targetId,
            List<Node> peers,
            Set<Node> alreadyChecked,
            int ttl
    ) {
        if (ttl <= 0) {
            List<Node> closestNodes = new ArrayList<>(alreadyChecked);
            return CompletableFuture.completedFuture(Optional.ofNullable(closestNodes.isEmpty() ? null : closestNodes.get(0)));
        }

        Utils nodeComparator = new Utils(this);
        peers.sort(nodeComparator);

        List<CompletableFuture<List<Node>>> tasks = new ArrayList<>();
        Queue<Node> closerPeers = new ConcurrentLinkedQueue<>();

        for (Node peer : peers) {
            if (!alreadyChecked.contains(peer)) {
                alreadyChecked.add(peer);

                tasks.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        List<Node> response = sendFindNodeRequest(peer, targetId).join();
                        if (!response.isEmpty() && response.get(0).getId().compareTo(targetId) == 0) {
                            return List.of(response.get(0));
                        } else {
                            closerPeers.addAll(response);
                            return List.of();
                        }
                    } catch (Exception e) {
                        return List.of();
                    }
                }, executorService));
            }
        }

        return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                .thenCompose(v -> {
                    for (CompletableFuture<List<Node>> task : tasks) {
                        List<Node> result = task.join();
                        if (!result.isEmpty()) {
                            Node firstNode = result.get(0);
                            // Check if the first node is the target
                            if (firstNode.getId().equals(targetId)) {
                                return CompletableFuture.completedFuture(Optional.of(firstNode));
                            }
                        }
                    }

                    List<Node> closerPeersList = new ArrayList<>();
                    for (CompletableFuture<List<Node>> task : tasks) {
                        List<Node> result = task.join();
                        closerPeersList.addAll(result);
                    }

                    Set<Node> uniquePeers = closerPeersList.stream()
                            .filter(n -> !alreadyChecked.contains(n))
                            .collect(Collectors.toSet());

                    return findNodeRecursive(targetId, new ArrayList<>(uniquePeers), alreadyChecked, ttl - 1)
                            .thenApply(optionalNode -> {
                                if (optionalNode.isEmpty()) {
                                    List<Node> closestNodes = new ArrayList<>(alreadyChecked);
                                    return Optional.ofNullable(closestNodes.isEmpty() ? null : closestNodes.get(0));
                                }
                                return optionalNode;
                            });
                });
    }



    private CompletableFuture<List<Node>> sendFindNodeRequest(Node peer, BigInteger targetId) {
        return CompletableFuture.supplyAsync(() -> {
            return peer.findClosestNodes(targetId, this.K);
        });
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