package Utils;


public class StoreValue {
    public enum Type {
        AUCTION,
        SUBSCRIPTION,
        BID,
        CLOSE
    }

    public StoreValue(Type type, String payload){
        this.type = type;
        this.payload = payload;
    }

    private Type type;
    private String payload;

    public String getPayload(){
        return this.payload;
    }

    public Type getType(){
        return this.type;
    }

}
