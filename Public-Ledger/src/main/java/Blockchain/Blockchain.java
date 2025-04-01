package Blockchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Blockchain {

    private List<Block> chain;

    public Blockchain() {

        chain = new ArrayList<>();

        List<Transaction> firstTransaction = new ArrayList<>();
        firstTransaction.add(null);

        Block genesisBlock = new Block(0,"", firstTransaction);

        chain.add(genesisBlock);
    }

    public void AddNewBlock(List<Transaction> transactions) {

        Block lastBlock = this.GetLastBlock();

        int blockIndex = this.chain.size();
        Block newBlock = new Block(blockIndex, lastBlock.getBlockHash(), transactions);

        newBlock.mine();

        this.chain.add(newBlock);
    }

    public boolean validateBlockChain(){

        for(int i = 1; i < chain.size(); i++){
            Block previousBlock = this.chain.get(i-1);
            Block presentBlock = this.chain.get(i);

            String presentBlockHash = presentBlock.getBlockHash();

            if (!Objects.equals(presentBlock.CalculateBlockHash(), presentBlockHash)) {
                return false;
            }
            if (!presentBlockHash.startsWith("0".repeat(presentBlock.getDifficulty()))) {
                return false;
            }
            if (!Objects.equals(previousBlock.getBlockHash(), presentBlock.getPreviousBlockHash())) {
                return false;
            }


        }
        return true;
    }

    private Block GetLastBlock(){
        int lastBlockIndex = this.chain.size() - 1;
        return this.chain.get(lastBlockIndex);
    }
}
