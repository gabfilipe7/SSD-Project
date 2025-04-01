package org.example;

import Blockchain.Block;

import java.util.List;

public class Main {
    public static void main(String[] args) {
        System.out.println("Mining Block 1...");
        Block genesisBlock = new Block(0, "0", (null));
        genesisBlock.mine();

        System.out.println( "Mining Block 2...");
        Block secondBlock = new Block(1, genesisBlock.getBlockHash(), (null));
        secondBlock.mine();
    }
}
