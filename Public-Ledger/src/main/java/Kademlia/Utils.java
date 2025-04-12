package Kademlia;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class Utils implements Comparator<Node> {

    private Node currentNode;

    public Utils(Node currentNode) {
        this.currentNode = currentNode;
    }

    @Override
    public int compare(Node node1, Node node2) {
        BigInteger distance1 = currentNode.xorDistance(node1.getId());
        BigInteger distance2 = currentNode.xorDistance(node2.getId());

        return distance1.compareTo(distance2);
    }


    private List<Node> sortByDistance(List<Node> peers, BigInteger targetId) {
        return peers.stream()
                .sorted(Comparator.comparing(peer -> peer.getId().xor(targetId)))
                .collect(Collectors.toList());
    }


}
