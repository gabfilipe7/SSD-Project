package Utils;

public class StoreValue {

    public enum Type {
        AUCTION,
        SUBSCRIPTION,
        BID,
        CLOSE,
        ADD_CATALOG,
        REMOVE_CATALOG
    }

    public StoreValue(Type type, String payload){
        this.Type = type;
        this.Payload = payload;
    }

    private Type Type;

    private String Payload;

    public String getPayload(){
        return this.Payload;
    }

    public Type getType(){
        return this.Type;
    }

}
