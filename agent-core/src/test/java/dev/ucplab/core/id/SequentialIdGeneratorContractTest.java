package dev.ucplab.core.id;

/** {@link SequentialIdGenerator} runs the id generator contract (H3 P4; Appendix B, 7.4). It claims no UUID shape. */
class SequentialIdGeneratorContractTest extends IdGeneratorContract {

    @Override
    protected IdGenerator create() {
        return new SequentialIdGenerator("t");
    }
}
