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
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
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
}
