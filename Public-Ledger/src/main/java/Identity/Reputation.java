package Identity;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.time.Instant;
import java.time.Instant;
import java.util.UUID;

public class Reputation {
    private UUID reputationId;
    private double score;
    private Instant lastUpdated;

    public Reputation(double score, Instant lastUpdated) {
        this.score = score;
        this.lastUpdated = lastUpdated;
    }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public void setReputationId(UUID reputationId) { this.reputationId = reputationId; }
    public UUID getReputationId() { return this.reputationId;  }

    public Instant getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }

    public byte[] signReputation(PrivateKey privateKey, BigInteger nodeId) {
        try {
            Security.addProvider(new BouncyCastleProvider());

            String data = nodeId.toString() + score + lastUpdated.toString();
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
