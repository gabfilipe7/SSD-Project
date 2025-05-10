package Kademlia;

import java.util.ArrayList;
import java.util.List;

public class KBucket {

    private int K;
    private List<Node> Nodes;

    public KBucket(int k) {
        this.K = k;
        this.Nodes = new ArrayList<>();
    }

    public boolean addNode(Node node) {
        if (Nodes.contains(node)) {
            Nodes.remove(node);
            Nodes.add(0, node);
            return true;
        }

        if (Nodes.size() < K) {
            Nodes.add(0, node);
            return true;
        }

        Node last = Nodes.get(Nodes.size()-1);
        if (!RpcClient.ping(last)) {
            Nodes.remove(last);
            Nodes.add(0, node);
            return true;
        }
        return false;
    }
    public List<Node> getNodes() {
        return this.Nodes;
    }
    public int getK() {
        return this.K;
    }




}
