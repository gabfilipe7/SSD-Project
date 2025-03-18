package Blockchain;
import java.util.Date;
import java.util.List;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;

public class Block {

    private String BlockHash;
    private String PreviousBlockHash;
    private long Timestamp;
    private List<Integer> Transactions;
    private long Nonce;

    public Block(String previousBlockHash, List<Integer> transactions ) {
        this.PreviousBlockHash = previousBlockHash;
        this.Timestamp = new Date().getTime();
        this.Transactions = transactions;
        this.Nonce = 0;
        this.BlockHash = this.CalculateBlockHash();
    }

    private String CalculateBlockHash(){
        try{
            String content = this.PreviousBlockHash + this.Nonce + this.Timestamp + this.Transactions;

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

    public long getTimestamp() {
        return Timestamp;
    }

    public long getNonce() {
        return Nonce;
    }

    public String getBlockHash() {
        return BlockHash;
    }

    public String getPreviousBlockHash() {
        return PreviousBlockHash;
    }

    public List<Integer> getTransactions() {
        return Transactions;
    }
}