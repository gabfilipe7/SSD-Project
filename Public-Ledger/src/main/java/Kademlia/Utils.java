package Kademlia;

import Blockchain.Blockchain;
import Blockchain.Transaction;
import Blockchain.Block;
import com.google.protobuf.ByteString;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jcajce.provider.digest.SHA256;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Security;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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

    public static PublicKey byteStringToPublicKey(ByteString byteString) {
        try {
            Security.addProvider(new BouncyCastleProvider());
            byte[] keyBytes = byteString.toByteArray();
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA", "BC");
            return keyFactory.generatePublic(spec);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public static ByteString publicKeyToByteString(PublicKey publicKey) {
        try {
            byte[] keyBytes = publicKey.getEncoded();
            return ByteString.copyFrom(keyBytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Block convertResponseToBlock(com.kademlia.grpc.Block receivedBlock){

        List<Transaction> transactions = new ArrayList<>();

        for (com.kademlia.grpc.Transaction tr : receivedBlock.getTransactionsList()){
            transactions.add(convertResponseToTransaction(tr));
        }
        return new Block(receivedBlock.getBlockId(),
                receivedBlock.getHash(),
                receivedBlock.getPreviousHash(),
                receivedBlock.getTimestamp(),
                transactions,
                receivedBlock.getNonce());
    }

    public static com.kademlia.grpc.Block convertBlockToResponse(Block block) {
        com.kademlia.grpc.Block.Builder builder = com.kademlia.grpc.Block.newBuilder();

        builder.setBlockId(block.getIndex());
        builder.setHash(block.getBlockHash());
        builder.setPreviousHash(block.getPreviousBlockHash());
        builder.setTimestamp(block.getTimestamp());
        builder.setNonce(block.getNonce());

        for (Transaction tx : block.getTransactions()) {
            builder.addTransactions(convertTransactionToResponse(tx));
        }

        return builder.build();
    }


    public static Transaction convertResponseToTransaction(com.kademlia.grpc.Transaction transaction){
        return new Transaction(UUID.fromString(transaction.getTransactionId()),
                Transaction.TransactionType.values()[transaction.getType()],
                Instant.parse(transaction.getTimestamp()),
                Utils.byteStringToPublicKey(transaction.getSenderPublicKey()));
    }

    public static com.kademlia.grpc.Transaction convertTransactionToResponse(Transaction transaction) {
        return com.kademlia.grpc.Transaction.newBuilder()
                .setTransactionId(transaction.getTransactionId().toString())
                .setType(transaction.getType().ordinal())
                .setTimestamp(transaction.getTimestamp().toString())
                .setSenderPublicKey(Utils.publicKeyToByteString(transaction.getSender()))
                .build();
    }

    public static String calculateChainHash(Blockchain blockchain) {

        StringBuilder sb = new StringBuilder();
        for (Block block : blockchain.getChain()) {
            sb.append(block.getBlockHash());
        }
        return sb.toString();
    }

    public static BigInteger hashKeyToId(String key) {
        SHA256Digest digest = new SHA256Digest();
        byte[] inputBytes = key.getBytes(StandardCharsets.UTF_8);
        digest.update(inputBytes, 0, inputBytes.length);

        byte[] hashBytes = new byte[digest.getDigestSize()];
        digest.doFinal(hashBytes, 0);

        return new BigInteger(1, hashBytes);
    }
}
