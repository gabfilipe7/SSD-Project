package Utils;

import Blockchain.Blockchain;
import Blockchain.Transaction;
import Blockchain.Block;
import Kademlia.Node;
import com.google.gson.*;
import com.google.protobuf.ByteString;
import com.kademlia.grpc.BlockMessage;
import com.kademlia.grpc.TransactionMessage;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class Utils implements Comparator<Node> {


    private BigInteger currentNodeId;

    static Gson gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .registerTypeHierarchyAdapter(PublicKey.class, new PublicKeyAdapter())
            .create();

    public Utils(BigInteger currentNodeId) {
        this.currentNodeId = currentNodeId;
    }

    @Override
    public int compare(Node node1, Node node2) {
        BigInteger distance1 = currentNodeId.xor(node1.getId());
        BigInteger distance2 = currentNodeId.xor(node2.getId());

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

        for(String tr: receivedBlock.getBlockData().getTransactionsList()){
            transactions.add(gson.fromJson(tr,Transaction.class));
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

        List<String> transactions = new ArrayList<>();

        for(Transaction tr: block.getTransactions()){
            transactions.add(gson.toJson(tr));
        }

        builder.addAllTransactions(transactions);

        return builder.build();
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

    public static String sha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public static BigInteger generateRandomNodeId() {
        SecureRandom random = new SecureRandom();
        byte[] idBytes = new byte[20];
        random.nextBytes(idBytes);
        return new BigInteger(1, idBytes);
    }

}
