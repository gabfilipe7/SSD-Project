package Blockchain;

import java.util.ArrayList;
import java.util.List;

public class Blockchain {

    private List<Block> chain;

    public Blockchain() {

        chain = new ArrayList<>();

        List<Integer> firstTransaction = new ArrayList<>();
        firstTransaction.add(0);

        Block genesisBlock = new Block("", firstTransaction);

        chain.add(genesisBlock);
    }

    public void AddNewBlock(List<Integer> transactions) {

        Block lastBlock = this.GetLastBlock();

        Block newBlock = new Block(lastBlock.getBlockHash(), transactions);

        this.chain.add(newBlock);
    }

    private Block GetLastBlock(){
        int lastBlockIndex = this.chain.size() - 1;
        return this.chain.get(lastBlockIndex);
    }
}
