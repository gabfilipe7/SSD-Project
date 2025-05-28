package Utils;

import com.google.gson.*;
import java.lang.reflect.Type;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

public class PublicKeyAdapter implements JsonSerializer<PublicKey>, JsonDeserializer<PublicKey> {

    @Override
    public JsonElement serialize(PublicKey src, Type typeOfSrc, JsonSerializationContext context) {
        return new JsonPrimitive(Base64.getEncoder().encodeToString(src.getEncoded()));
    }

    @Override
    public PublicKey deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(json.getAsString());
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA", "BC");
            return keyFactory.generatePublic(spec);
        } catch (Exception e) {
            return null;
        }
    }

}
