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
        int size = this.Nodes.size();
        if(getNodes().contains(node)){
            return false;
        }
        if (size< this.K) {
            this.Nodes.add(node);
            return true;
        } else {
            this.Nodes.remove(size - 1);
            this.Nodes.add( node);
            return false;
        }
    }

    public synchronized Node getLeastRecentlySeenNode() {
        if (Nodes.isEmpty()) return null;
        return Nodes.get(0);
    }

    public synchronized void removeNode(Node node) {
        Nodes.remove(node);
    }

    public List<Node> getNodes() {
        return this.Nodes;
    }
    public int getK() {
        return this.K;
    }




}
