package dev.ucplab.core.approval;

class InMemoryApprovalRepositoryTest extends ApprovalRepositoryContract {

    @Override
    protected ApprovalRepository create() {
        return new InMemoryApprovalRepository();
    }
}
