package Blockchain;
import java.util.Date;
import java.util.List;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;

public class Block {

    private final long Index;
    private String BlockHash;
    private final String PreviousBlockHash;
    private final long Timestamp;
    private final List<Transaction> Transactions;
    private long Nonce;
    private final int Difficulty = 4;

    public Block(long index, String previousBlockHash, List<Transaction> transactions ) {
        this.PreviousBlockHash = previousBlockHash;
        this.Timestamp = new Date().getTime();
        this.Transactions = transactions;
        this.Nonce = 0;
        this.Index = index;
    }


    public Block(long index,
                 String blockHash,
                 String previousBlockHash,
                 long timestamp,
                 List<Transaction> transactions,
                 long nonce) {
        this.Index = index;
        this.BlockHash = blockHash;
        this.PreviousBlockHash = previousBlockHash;
        this.Timestamp = timestamp;
        this.Transactions = transactions;
        this.Nonce = nonce;
    }

    public String CalculateBlockHash(){
        try{
            String content = this.PreviousBlockHash + this.Index + this.Nonce + this.Timestamp + this.Transactions;

            Security.addProvider(new BouncyCastleProvider());

            MessageDigest digest = MessageDigest.getInstance("SHA-256", "BC");

            byte[] hashBytes = digest.digest(content.getBytes());

            StringBuilder hexString = new StringBuilder();

            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void mine(){
        String pattern = "0".repeat(this.Difficulty);
        while (true) {
            String hash = this.CalculateBlockHash();
            if (hash.startsWith(pattern)) {
                this.BlockHash = hash;
                break;
            }
            this.Nonce++;
        }
    }



    public long getTimestamp() {
        return Timestamp;
    }

    public long getNonce() {
        return Nonce;
    }

    public String getBlockHash() {
        return BlockHash;
    }

    public int getDifficulty() {
        return Difficulty;
    }

    public String getPreviousBlockHash() {
        return PreviousBlockHash;
    }

    public long getIndex() {
        return Index;
    }

    public List<Transaction> getTransactions() {
        return Transactions;
    }
}