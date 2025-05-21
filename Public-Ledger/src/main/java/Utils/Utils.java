package Utils;

import Blockchain.Blockchain;
import Blockchain.Transaction;
import Blockchain.Block;
import Kademlia.Node;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.protobuf.ByteString;
import com.kademlia.grpc.BlockMessage;
import com.kademlia.grpc.TransactionMessage;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

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

    public static Block convertResponseToBlock(BlockMessage receivedBlock){

        com.kademlia.grpc.Block block = receivedBlock.getBlockData();

        List<Transaction> transactions = new ArrayList<>();

        for (com.kademlia.grpc.Transaction tr : block.getTransactionsList()){
            transactions.add(convertResponseToTransaction(tr));
        }

        return new Block(block.getBlockId(),
                block.getHash(),
                block.getPreviousHash(),
                block.getTimestamp(),
                transactions,
                block.getNonce());
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


    public static Transaction convertResponseToTransaction(com.kademlia.grpc.Transaction transaction) {
        Transaction tx = new Transaction();

        if (!transaction.getTransactionId().isEmpty()) {
            tx.setTransactionId(UUID.fromString(transaction.getTransactionId()));
        }
        tx.setType(Transaction.TransactionType.values()[transaction.getType()]);
        if (!transaction.getTimestamp().isEmpty()) {
            tx.setTimestamp(Instant.parse(transaction.getTimestamp()));
        }
        if (!transaction.getSenderPublicKey().isEmpty()) {
            tx.setSender(Utils.byteStringToPublicKey(transaction.getSenderPublicKey()));
        }
        if (!transaction.getAuctionId().isEmpty()) {
            tx.setAuctionId(UUID.fromString(transaction.getAuctionId()));
        }
        if (!transaction.getAmount().isEmpty()) {
            tx.setAmount(Double.parseDouble(transaction.getAmount()));
        }
        if (!transaction.getSignature().isEmpty()) {
            tx.setSignature(transaction.getSignature().toByteArray());
        }
        return tx;
    }


    public static com.kademlia.grpc.Transaction convertTransactionToResponse(Transaction transaction) {
        com.kademlia.grpc.Transaction.Builder protoTxBuilder = com.kademlia.grpc.Transaction.newBuilder();

        protoTxBuilder.setTransactionId(transaction.getTransactionId() != null ? transaction.getTransactionId().toString() : "")
                .setType(transaction.getType() != null ? transaction.getType().ordinal() : 0)
                .setTimestamp(transaction.getTimestamp() != null ? transaction.getTimestamp().toString() : "")
                .setSenderPublicKey(transaction.getSender() != null ? Utils.publicKeyToByteString(transaction.getSender()) : ByteString.EMPTY)
                .setAuctionId(transaction.getAuctionId() != null ? transaction.getAuctionId().toString() : "")
                .setAmount(transaction.getAmount() != null ? transaction.getAmount().toString() : "")
                .setSignature((transaction.getSignature() != null ? ByteString.copyFrom(transaction.getSignature())  : ByteString.EMPTY));


        return protoTxBuilder.build();
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

    public static JsonElement PublicKeySerializer(PublicKey src) {
        String base64 = Base64.getEncoder().encodeToString(src.getEncoded());
        return new JsonPrimitive(base64);
    }
    public static PublicKey PublicKeyDeserializer(JsonElement json)
            throws JsonParseException {
        try {
            byte[] bytes = Base64.getDecoder().decode(json.getAsString());
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(new X509EncodedKeySpec(bytes));
        } catch (Exception e) {
            throw new JsonParseException(e);
        }
    }

    public static String sha256(String input) {
        SHA256Digest digest = new SHA256Digest();
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        digest.update(inputBytes, 0, inputBytes.length);

        byte[] hash = new byte[digest.getDigestSize()];
        digest.doFinal(hash, 0);

        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }

    public static BigInteger generateRandomNodeId() {
        SecureRandom random = new SecureRandom();
        byte[] idBytes = new byte[20];
        random.nextBytes(idBytes);
        return new BigInteger(1, idBytes);
    }

}
