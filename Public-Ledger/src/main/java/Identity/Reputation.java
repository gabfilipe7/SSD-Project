package Identity;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public class Reputation {

    private UUID ReputationId;
    private double Score;
    private Instant LastUpdated;

    public Reputation(double score, Instant lastUpdated) {
        this.Score = score;
        this.LastUpdated = lastUpdated;
    }

    public double getScore() { return Score; }

    public void setScore(double score) { this.Score = score; }

    public void setReputationId(UUID reputationId) { this.ReputationId = reputationId; }

    public UUID getReputationId() { return this.ReputationId;  }

    public void generateId() {
        this.ReputationId = UUID.randomUUID();
    }

    public Instant getLastUpdated() { return LastUpdated; }

    public void setLastUpdated(Instant lastUpdated) { this.LastUpdated = lastUpdated; }

    public byte[] signReputation(PrivateKey privateKey, BigInteger nodeId) {
        try {
            Security.addProvider(new BouncyCastleProvider());

            String data = nodeId.toString() + Score + LastUpdated.truncatedTo(ChronoUnit.SECONDS).toString();
            byte[] message = data.getBytes(StandardCharsets.UTF_8);
            Signature signature = Signature.getInstance("SHA256withRSA", "BC");
            signature.initSign(privateKey);
            signature.update(message);

            return signature.sign();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign reputation", e);
        }
    }

}
